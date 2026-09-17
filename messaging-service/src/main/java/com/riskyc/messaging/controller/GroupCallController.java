package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.GroupCallEndedRequest;
import com.riskyc.messaging.dto.GroupCallInvite;
import com.riskyc.messaging.dto.GroupCallInviteRequest;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The ONLY coupling between sfu-service (the group-calling SFU, a separate
 * Node.js service — see backend/sfu-service/) and this Spring backend.
 * sfu-service owns all call-session/media state itself and never touches
 * Postgres directly; it calls these two endpoints to (1) fan out an invite
 * when a room is first created and (2) log a call-summary chat message once
 * a room empties. Shared-secret protected exactly like SessionController's
 * /internal/sessions/revoked-jtis — this is the first such "another service
 * calls INTO messaging-service" internal endpoint (until now messaging-service
 * only ever called OUT to auth-service), same protection mechanism reused.
 */
@RestController
public class GroupCallController {

    private final GroupMemberRepository groupMemberRepository;
    private final MessageRepository messageRepository;
    private final PushNotificationService pushNotificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final String internalApiKey;

    public GroupCallController(GroupMemberRepository groupMemberRepository, MessageRepository messageRepository,
                                PushNotificationService pushNotificationService, SimpMessagingTemplate messagingTemplate,
                                @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.groupMemberRepository = groupMemberRepository;
        this.messageRepository = messageRepository;
        this.pushNotificationService = pushNotificationService;
        this.messagingTemplate = messagingTemplate;
        this.internalApiKey = internalApiKey;
    }

    /**
     * sfu-service forwards whatever member list the CALLING CLIENT gave it —
     * deliberately not trusted as-is: validated here against real group
     * membership before anyone gets pushed/STOMP-notified, so a buggy or
     * malicious caller can't get this endpoint to notify arbitrary users.
     */
    @PostMapping("/internal/group-calls/invite")
    public void invite(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                        @RequestBody GroupCallInviteRequest request) {
        requireInternalKey(apiKey);

        Set<String> actualMemberIds = groupMemberRepository.findByGroupId(request.groupId()).stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toSet());

        GroupCallInvite outbound = new GroupCallInvite(request.groupId(), request.groupId(), request.callerId(),
                request.callerName(), request.callType());
        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("type", "group-call");
        pushData.put("groupId", request.groupId());
        pushData.put("callerId", request.callerId());
        pushData.put("callerName", request.callerName());
        pushData.put("callType", request.callType());

        String callerLabel = request.callerName() != null && !request.callerName().isBlank() ? request.callerName() : "Someone";
        String callKind = "VIDEO".equals(request.callType()) ? "video call" : "voice call";

        for (String memberId : request.memberIds()) {
            if (!actualMemberIds.contains(memberId) || memberId.equals(request.callerId())) {
                continue;
            }
            messagingTemplate.convertAndSendToUser(memberId, "/queue/calls", outbound,
                    Map.of("callMessageType", "group-invite"));
            pushNotificationService.sendToUser(memberId, callerLabel, "Incoming group " + callKind, "calls", pushData,
                    "incoming_group_call");
        }
    }

    /** Fires once a room's last peer leaves — logs a call-summary message into the group conversation, same shape CallController#logCallAsMessage uses for 1:1, generalized for N participants. */
    @PostMapping("/internal/group-calls/ended")
    public void ended(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                       @RequestBody GroupCallEndedRequest request) {
        requireInternalKey(apiKey);

        List<String> actualMemberIds = groupMemberRepository.findByGroupId(request.groupId()).stream()
                .map(GroupMember::getUserId)
                .toList();
        if (actualMemberIds.isEmpty()) {
            // Group no longer exists (or never did) — nothing to log against.
            return;
        }

        long durationMs = Duration.between(request.startedAt(), request.endedAt()).toMillis();
        // A group call has no single caller/callee pair, so senderId is
        // nominal here (the first actual member, purely to satisfy the
        // column's NOT NULL constraint) — the client renders group-call log
        // entries by mediaParticipantCount/groupId, not by sender.
        String nominalSenderId = actualMemberIds.get(0);

        Message callLog = new Message(UUID.randomUUID().toString(), request.groupId(), nominalSenderId,
                request.groupId(), "ENDED", request.startedAt());
        callLog.setGroupId(request.groupId());
        callLog.setMediaType(Message.MediaType.CALL);
        callLog.setMediaFileName(request.callType());
        callLog.setMediaDurationMs((int) durationMs);
        callLog.setMediaParticipantCount(request.participantIds().size());
        messageRepository.save(callLog);

        MessageEnvelope envelope = new MessageEnvelope(callLog.getMessageId(), request.groupId(), nominalSenderId,
                request.groupId(), callLog.getCiphertext(), callLog.getSentAt(), callLog.getStatus().name(),
                "CALL", null, callLog.getMediaFileName(), callLog.getMediaDurationMs(), false, false,
                request.groupId(), false, List.of(), null, null, null, null, false, request.participantIds().size(),
                null, null);

        messagingTemplate.convertAndSend("/topic/conversation." + request.groupId(), envelope);
        for (String memberId : actualMemberIds) {
            messagingTemplate.convertAndSendToUser(memberId, "/queue/messages", envelope);
        }
    }

    private void requireInternalKey(String apiKey) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
