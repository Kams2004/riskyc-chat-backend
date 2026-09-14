package com.riskyc.messaging.controller;

import com.riskyc.messaging.dto.CallAnswer;
import com.riskyc.messaging.dto.CallEnd;
import com.riskyc.messaging.dto.CallIceCandidate;
import com.riskyc.messaging.dto.CallInvite;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.repository.CallRepository;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;

    public CallController(CallRepository callRepository, SimpMessagingTemplate messagingTemplate,
                           PushNotificationService pushNotificationService) {
        this.callRepository = callRepository;
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
        });
    }

    private Map<String, Object> headersFor(String callMessageType) {
        return Map.of("callMessageType", callMessageType);
    }
}
