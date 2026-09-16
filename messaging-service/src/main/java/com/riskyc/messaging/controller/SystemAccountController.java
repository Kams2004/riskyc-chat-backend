package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.SystemWelcomeRequest;
import com.riskyc.messaging.entity.Message;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Called by auth-service right after a brand-new account is created (see
 * SystemAccountService there) to deliver that account's first message — the
 * RiskyC Fashion system account's welcome. Same shared-secret protection as
 * GroupCallController's internal endpoints; this is auth-service's mirror
 * of that "another service calls INTO messaging-service" pattern.
 */
@RestController
public class SystemAccountController {

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;
    private final String internalApiKey;

    public SystemAccountController(MessageRepository messageRepository, SimpMessagingTemplate messagingTemplate,
                                    PushNotificationService pushNotificationService,
                                    @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.internalApiKey = internalApiKey;
    }

    @PostMapping("/internal/system-account/welcome")
    public void welcome(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                         @RequestBody SystemWelcomeRequest request) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }

        String messageId = UUID.randomUUID().toString();
        Instant sentAt = Instant.now();
        Message message = new Message(messageId, request.conversationId(), request.senderId(),
                request.recipientId(), request.text(), sentAt);
        messageRepository.save(message);

        MessageEnvelope outbound = new MessageEnvelope(messageId, request.conversationId(), request.senderId(),
                request.recipientId(), request.text(), sentAt, message.getStatus().name(), null, null, null, null,
                false, false, null, false, List.of(), null, null, null, null, false, null);
        messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId(), outbound);
        messagingTemplate.convertAndSendToUser(request.recipientId(), "/queue/messages", outbound);

        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("type", "message");
        pushData.put("conversationId", request.conversationId());
        pushData.put("senderId", request.senderId());
        if (request.senderAvatarObjectKey() != null) {
            pushData.put("avatarObjectKey", request.senderAvatarObjectKey());
        }
        String title = request.senderDisplayName() != null ? request.senderDisplayName() : "RiskyC Chat";
        pushNotificationService.sendToUser(request.recipientId(), title, request.text(), "messages", pushData);
    }
}
