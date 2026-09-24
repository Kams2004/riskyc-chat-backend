package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.dto.SystemWelcomeRequest;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Called by auth-service right after a brand-new account is created (see
 * SystemAccountService there) to deliver that account's first message — the
 * RiskyC Fashion system account's welcome. Same shared-secret protection as
 * GroupCallController's internal endpoints; this is auth-service's mirror
 * of that "another service calls INTO messaging-service" pattern.
 *
 * Also hosts the official account's client-facing broadcast endpoint (see
 * #broadcast below) — same controller, since both concern the one system
 * account, but a completely different caller (a real signed-in client, not
 * another service) and auth model (JWT, not the shared internal key).
 */
@RestController
public class SystemAccountController {

    private static final Logger log = Logger.getLogger(SystemAccountController.class.getName());

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;
    private final String internalApiKey;
    private final String authServiceInternalUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public SystemAccountController(MessageRepository messageRepository, SimpMessagingTemplate messagingTemplate,
                                    PushNotificationService pushNotificationService, JwtIssuer jwtIssuer,
                                    RevokedJtiCache revokedJtiCache,
                                    @Value("${riskyc.internal.api-key:}") String internalApiKey,
                                    @Value("${riskyc.auth-service.internal-url}") String authServiceInternalUrl) {
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
        this.internalApiKey = internalApiKey;
        this.authServiceInternalUrl = authServiceInternalUrl;
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
                null, null, false, false, null, false, List.of(), null, null, null, null, false, null,
                request.senderDisplayName(), null, false, null, null);
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
        pushNotificationService.sendToUser(request.recipientId(), title, request.text(), "messages-v3", pushData);
    }

    public record BroadcastRequest(String text, String mediaType, String mediaObjectKey, String mediaFileName,
                                    Integer mediaDurationMs, String senderDisplayName, String senderAvatarObjectKey) {
    }

    /**
     * Client-facing — a real signed-in user's JWT, not the internal key.
     * Only the official account itself may call this (checked against
     * auth-service's own record of who that is, not a locally-cached
     * assumption). Fans one message out into every OTHER user's 1:1 with
     * this account, exactly like a normal ChatController#send, just looped:
     * same conversationId scheme (sorted pair), same persisted Message row,
     * same live STOMP delivery + push notification per recipient.
     *
     * "Read-only, official account can't be replied to" is enforced
     * client-side only (the composer is disabled when the other party is
     * the official account — see SystemAccountInfoController's public
     * lookup) — a deliberate MVP scope line, the same one this codebase
     * already draws elsewhere (e.g. media-service's presigned URLs have no
     * auth check at all yet). A client that skipped that check could still
     * send an ordinary 1:1 message into the same conversationId; nothing
     * server-side currently blocks that.
     */
    @PostMapping("/api/system-account/broadcast")
    public void broadcast(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody BroadcastRequest request) {
        String callerId = callerIdFrom(authorization);
        String systemAccountId = fetchSystemAccountId();
        if (systemAccountId == null || !systemAccountId.equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the official account can broadcast");
        }

        List<String> allUserIds = fetchAllUserIds();
        String title = request.senderDisplayName() != null ? request.senderDisplayName() : "RiskyC Chat";
        String previewBody = request.text() != null && !request.text().isBlank() ? request.text()
                : request.mediaType() != null ? "📎 " + request.mediaType() : "";

        for (String recipientId : allUserIds) {
            if (recipientId.equals(callerId)) {
                continue;
            }
            String conversationId = List.of(callerId, recipientId).stream().sorted().reduce((a, b) -> a + "_" + b).orElseThrow();
            String messageId = UUID.randomUUID().toString();
            Instant sentAt = Instant.now();

            Message message = new Message(messageId, conversationId, callerId, recipientId,
                    request.text() != null ? request.text() : "", sentAt);
            if (request.mediaType() != null) {
                message.setMediaType(Message.MediaType.valueOf(request.mediaType()));
                message.setMediaObjectKey(request.mediaObjectKey());
                message.setMediaFileName(request.mediaFileName());
                message.setMediaDurationMs(request.mediaDurationMs());
            }
            messageRepository.save(message);

            MessageEnvelope outbound = new MessageEnvelope(messageId, conversationId, callerId, recipientId,
                    message.getCiphertext(), sentAt, message.getStatus().name(), request.mediaType(),
                    request.mediaObjectKey(), request.mediaFileName(), request.mediaDurationMs(), null, null, false, false,
                    null, false, List.of(), null, null, null, null, false, null, request.senderDisplayName(), null,
                    false, null, null);
            messagingTemplate.convertAndSend("/topic/conversation." + conversationId, outbound);
            messagingTemplate.convertAndSendToUser(recipientId, "/queue/messages", outbound);

            Map<String, Object> pushData = new LinkedHashMap<>();
            pushData.put("type", "message");
            pushData.put("conversationId", conversationId);
            pushData.put("senderId", callerId);
            if (request.senderAvatarObjectKey() != null) {
                pushData.put("avatarObjectKey", request.senderAvatarObjectKey());
            }
            pushNotificationService.sendToUser(recipientId, title, previewBody, "messages-v3", pushData);
        }
    }

    private String fetchSystemAccountId() {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            URI uri = URI.create(authServiceInternalUrl + "/internal/system-account/id");
            var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<Void>(headers), Map.class);
            Object userId = response.getBody() != null ? response.getBody().get("userId") : null;
            return userId != null ? userId.toString() : null;
        } catch (RestClientException e) {
            log.log(Level.WARNING, "Failed to resolve the official account's id from auth-service", e);
            return null;
        }
    }

    private List<String> fetchAllUserIds() {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            URI uri = URI.create(authServiceInternalUrl + "/internal/users/all-ids");
            String[] body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<Void>(headers), String[].class).getBody();
            return body != null ? List.of(body) : List.of();
        } catch (RestClientException e) {
            log.log(Level.WARNING, "Failed to fetch the user list from auth-service for a broadcast", e);
            return List.of();
        }
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revokedJtiCache.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims.subject();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
