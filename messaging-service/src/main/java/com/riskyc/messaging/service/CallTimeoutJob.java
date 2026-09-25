package com.riskyc.messaging.service;

import com.riskyc.messaging.dto.CallEnd;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.repository.CallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A call only ever gets marked DECLINED/MISSED/ENDED — and logged into the
 * conversation as a message — when a client explicitly sends /call.end (see
 * CallController#end). That's fine for a normal hangup or decline, but an
 * unanswered call where the caller never taps "hang up" (backgrounds the
 * app, the OS kills it, a real phone call interrupts it, ...) leaves the
 * Call row stuck in RINGING forever — nobody logs it, so no "Missed call"
 * ever shows up in either party's thread. This sweeps those out server-side
 * so a missed call is always recorded regardless of what either client's
 * app does afterward.
 */
@Component
public class CallTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CallTimeoutJob.class);

    /** Comfortably longer than a real ring — long enough that a normal answer/decline always beats it, short enough that a genuinely unanswered call doesn't sit "ringing" for the other party indefinitely. */
    private static final Duration RING_TIMEOUT = Duration.ofSeconds(45);

    private final CallRepository callRepository;
    private final CallLogService callLogService;
    private final SimpMessagingTemplate messagingTemplate;

    public CallTimeoutJob(CallRepository callRepository, CallLogService callLogService, SimpMessagingTemplate messagingTemplate) {
        this.callRepository = callRepository;
        this.callLogService = callLogService;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRate = 15_000)
    public void sweep() {
        List<Call> stale = callRepository.findStaleRinging(Instant.now().minus(RING_TIMEOUT));
        for (Call call : stale) {
            call.setStatus(Call.CallStatus.MISSED);
            call.setEndedAt(Instant.now());
            callRepository.save(call);

            // Whichever side's UI is still showing "Ringing..." (most likely
            // the callee, but a caller who backgrounded rather than hung up
            // could still have it on screen too) needs to be told directly —
            // it never sent or received a real call.end for this callId.
            CallEnd outbound = new CallEnd(call.getId(), "system", "timeout");
            Map<String, Object> headers = Map.of("callMessageType", "end");
            messagingTemplate.convertAndSendToUser(call.getCallerId(), "/queue/calls", outbound, headers);
            messagingTemplate.convertAndSendToUser(call.getCalleeId(), "/queue/calls", outbound, headers);

            callLogService.logCallAsMessage(call);
        }
        if (!stale.isEmpty()) {
            log.info("Marked {} stale ringing call(s) as missed", stale.size());
        }
    }
}
