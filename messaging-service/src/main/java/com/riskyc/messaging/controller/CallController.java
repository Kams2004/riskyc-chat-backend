package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.CallAnswer;
import com.riskyc.messaging.dto.CallEnd;
import com.riskyc.messaging.dto.CallIceCandidate;
import com.riskyc.messaging.dto.CallInvite;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.CallRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WebRTC signaling relay for 1:1 calls — everything lands on the same
 * /user/queue/calls destination (one always-on subscription mobile's
 * CallContext keeps open, mirroring InboxSocket's /queue/messages), with a
 * "callMessageType" header telling the client which of invite/answer/ice/end
 * it's looking at, since the payload shapes differ. No SFU/media relay here:
 * this only exchanges SDP/ICE between the two peers, who then connect
 * directly (see architecture notes — same peer-to-peer model WebRTC assumes,
 * degraded reliability without a TURN server off the caller/callee's shared
 * network, deliberately deferred).
 */
@Controller
public class CallController {

    private final CallRepository callRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;

    public CallController(CallRepository callRepository, MessageRepository messageRepository,
                           SimpMessagingTemplate messagingTemplate, PushNotificationService pushNotificationService) {
        this.callRepository = callRepository;
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
    }

    @MessageMapping("/call.invite")
    public void invite(CallInvite inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        String fromUserId = principal.getName();
        String callId = inbound.callId() != null ? inbound.callId() : UUID.randomUUID().toString();
        Call call = new Call(callId, fromUserId, inbound.toUserId(), Call.CallType.valueOf(inbound.type()), Instant.now());
        callRepository.save(call);

        CallInvite outbound = new CallInvite(callId, fromUserId, inbound.toUserId(), inbound.type(), inbound.sdpOffer());
        messagingTemplate.convertAndSendToUser(inbound.toUserId(), "/queue/calls", outbound, headersFor("invite"));

        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("type", "call");
        pushData.put("callId", callId);
        String title = "VIDEO".equals(inbound.type()) ? "Incoming video call" : "Incoming voice call";
        pushNotificationService.sendToUser(inbound.toUserId(), title, "RiskyC Chat", "calls", pushData);
    }

    @MessageMapping("/call.answer")
    public void answer(CallAnswer inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            if (!principal.getName().equals(call.getCalleeId())) {
                return;
            }
            call.setStatus(Call.CallStatus.ACCEPTED);
            call.setAnsweredAt(Instant.now());
            callRepository.save(call);

            CallAnswer outbound = new CallAnswer(inbound.callId(), principal.getName(), inbound.sdpAnswer());
            messagingTemplate.convertAndSendToUser(call.getCallerId(), "/queue/calls", outbound, headersFor("answer"));
        });
    }

    @MessageMapping("/call.ice")
    public void ice(CallIceCandidate inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            String toUserId = call.otherParty(fromUserId);
            CallIceCandidate outbound = new CallIceCandidate(inbound.callId(), fromUserId, inbound.candidate(),
                    inbound.sdpMid(), inbound.sdpMLineIndex());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("ice"));
        });
    }

    @MessageMapping("/call.end")
    public void end(CallEnd inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        callRepository.findById(inbound.callId()).ifPresent(call -> {
            String fromUserId = principal.getName();
            if (call.getStatus() == Call.CallStatus.RINGING) {
                call.setStatus("declined".equals(inbound.reason()) ? Call.CallStatus.DECLINED : Call.CallStatus.MISSED);
            } else {
                call.setStatus(Call.CallStatus.ENDED);
            }
            call.setEndedAt(Instant.now());
            callRepository.save(call);

            String toUserId = call.otherParty(fromUserId);
            CallEnd outbound = new CallEnd(inbound.callId(), fromUserId, inbound.reason());
            messagingTemplate.convertAndSendToUser(toUserId, "/queue/calls", outbound, headersFor("end"));

            logCallAsMessage(call);
        });
    }

    /**
     * Drops a call summary into the conversation's message history — same
     * table, same delivery paths (topic broadcast + both parties' inbox
     * queues) as a real message, so it shows up inline with no separate
     * subscription or rendering plumbing needed beyond mobile checking for
     * mediaType "CALL". mediaFileName carries the call type (AUDIO/VIDEO,
     * repurposed — there's no dedicated column for it) and ciphertext
     * carries the outcome (ENDED/MISSED/DECLINED); mediaDurationMs is the
     * real talk time (0 if the call was never answered).
     */
    private void logCallAsMessage(Call call) {
        String conversationId = call.getCallerId().compareTo(call.getCalleeId()) < 0
                ? call.getCallerId() + "_" + call.getCalleeId()
                : call.getCalleeId() + "_" + call.getCallerId();
        long durationMs = call.getAnsweredAt() != null
                ? Duration.between(call.getAnsweredAt(), call.getEndedAt()).toMillis()
                : 0;

        Message callLog = new Message(UUID.randomUUID().toString(), conversationId, call.getCallerId(),
                call.getCalleeId(), call.getStatus().name(), call.getStartedAt());
        callLog.setMediaType(Message.MediaType.CALL);
        callLog.setMediaFileName(call.getType().name());
        callLog.setMediaDurationMs((int) durationMs);
        messageRepository.save(callLog);

        MessageEnvelope envelope = new MessageEnvelope(callLog.getMessageId(), conversationId, call.getCallerId(),
                call.getCalleeId(), callLog.getCiphertext(), callLog.getSentAt(), callLog.getStatus().name(),
                "CALL", null, callLog.getMediaFileName(), callLog.getMediaDurationMs(), false, false, null);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, envelope);
        messagingTemplate.convertAndSendToUser(call.getCallerId(), "/queue/messages", envelope);
        messagingTemplate.convertAndSendToUser(call.getCalleeId(), "/queue/messages", envelope);
    }

    private Map<String, Object> headersFor(String callMessageType) {
        return Map.of("callMessageType", callMessageType);
    }
}
