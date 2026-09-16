package com.riskyc.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskyc.messaging.entity.PushToken;
import com.riskyc.messaging.repository.PushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort push delivery via Expo's push service (which relays to FCM/APNs
 * itself — no direct Firebase SDK dependency here). Deliberately fire-and-forget:
 * a push failure must never fail or delay the STOMP send/call-invite path that
 * triggers it, since the live in-app delivery already happened by then — this
 * is purely the "the recipient's app isn't open right now" fallback.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final URI EXPO_PUSH_URL = URI.create("https://exp.host/--/api/v2/push/send");

    private final PushTokenRepository pushTokenRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PushNotificationService(PushTokenRepository pushTokenRepository, ObjectMapper objectMapper) {
        this.pushTokenRepository = pushTokenRepository;
        this.objectMapper = objectMapper;
    }

    public void sendToUser(String userId, String title, String body, String channelId, Map<String, Object> data) {
        sendToUser(userId, title, body, channelId, data, null);
    }

    /**
     * categoryId is what makes Expo/APNs/FCM actually render interactive
     * action buttons (e.g. Answer/Decline) on the notification itself — see
     * mobile's usePushNotifications.ts#ensureCallCategory, which registers a
     * category of the same identifier client-side. Null for every
     * notification type except an incoming call.
     */
    public void sendToUser(String userId, String title, String body, String channelId, Map<String, Object> data,
                            String categoryId) {
        for (PushToken pushToken : pushTokenRepository.findByUserId(userId)) {
            send(pushToken.getToken(), title, body, channelId, data, categoryId);
        }
    }

    private void send(String expoPushToken, String title, String body, String channelId, Map<String, Object> data,
                       String categoryId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", expoPushToken);
            payload.put("title", title);
            payload.put("body", body);
            payload.put("sound", "default");
            payload.put("channelId", channelId);
            payload.put("data", data);
            payload.put("priority", "high");
            if (categoryId != null) {
                payload.put("categoryId", categoryId);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(EXPO_PUSH_URL)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            log.warn("Expo push send failed ({}): {}", response.statusCode(), response.body());
                        }
                    })
                    .exceptionally(e -> {
                        log.warn("Expo push send errored", e);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Failed to build push payload", e);
        }
    }
}
