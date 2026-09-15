package com.riskyc.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Sends the OTP code over SMS via Bird (https://bird.com/docs/guides/sms/sending-sms).
 * Configured entirely through environment variables, mirroring
 * EmailOtpSender — swapping providers later is a config change, not a code
 * change. Synchronous, not fire-and-forget (unlike PushNotificationService):
 * an OTP request's entire point is that the code actually gets sent, so the
 * caller needs to know if Bird rejected it, not silently succeed anyway.
 *
 * Falls back to logging (dev/local use only) when no API key is configured,
 * rather than attempting a doomed call with blank credentials — that exact
 * gap in EmailOtpSender caused a real incident (a blank-credentials SMTP
 * call surfacing as an opaque 500) before it was tracked down.
 */
@Service
public class SmsOtpSender {

    private static final Logger log = Logger.getLogger(SmsOtpSender.class.getName());

    private final String apiKey;
    private final String apiBaseUrl;
    private final String sender;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public SmsOtpSender(@Value("${riskyc.sms.bird-api-key:}") String apiKey,
                         @Value("${riskyc.sms.bird-api-base-url}") String apiBaseUrl,
                         @Value("${riskyc.sms.sender}") String sender,
                         ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    public void sendOtp(String phoneNumber, String code) {
        if (apiKey.isBlank()) {
            log.warning(() -> "RISKYC_BIRD_API_KEY not configured — logging OTP instead of sending SMS (dev/local only)");
            System.out.printf("OTP for %s: %s%n", phoneNumber, code);
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", phoneNumber);
            payload.put("from", sender);
            payload.put("text", "Your RiskyC Chat verification code is " + code + ". It expires in 5 minutes.");
            payload.put("category", "authentication");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/v1/sms/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // A unique key per logical send — safe to retry this exact
                    // request (e.g. after a network blip) without risking a
                    // duplicate SMS; see Bird's idempotency guide.
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 202 Accepted means Bird has the message and is processing it —
            // not that it's delivered yet (see Bird's async delivery model).
            if (response.statusCode() != 202) {
                throw new IllegalStateException("Bird SMS send failed (" + response.statusCode() + "): " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reach Bird SMS API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending SMS via Bird", e);
        }
    }
}
