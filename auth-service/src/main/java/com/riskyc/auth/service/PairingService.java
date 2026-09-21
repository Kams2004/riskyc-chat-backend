package com.riskyc.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs the QR "link this browser as a device" flow — WhatsApp Web-style,
 * scoped to a single auth-service instance for now (same in-memory MVP
 * tradeoff as DeviceSwitchService; move to Redis before running more than
 * one replica). A web client starts a pairing, renders the returned token
 * as a QR code, and polls status; the already-signed-in mobile app scans
 * it and approves, which is what actually mints the new session/JWT
 * (mobile only ever proves ITS OWN identity — the web session it's
 * creating is a side effect of that, never something mobile receives or
 * handles itself).
 */
@Service
public class PairingService {

    private static final Duration PENDING_TTL = Duration.ofMinutes(3);
    // Short — this only needs to survive until the web client's next poll
    // picks up the result, not stay valid indefinitely once issued.
    private static final Duration APPROVED_TTL = Duration.ofSeconds(30);

    public enum Status { PENDING, APPROVED, DENIED }

    public record ApprovedResult(String accessToken, String userId, String displayName, String avatarObjectKey,
                                  String email, String phoneNumber) {
    }

    public record Entry(String deviceLabel, Status status, ApprovedResult result, Instant expiresAt) {
        Entry approved(ApprovedResult result) {
            return new Entry(deviceLabel, Status.APPROVED, result, Instant.now().plus(APPROVED_TTL));
        }

        Entry denied() {
            return new Entry(deviceLabel, Status.DENIED, null, Instant.now().plus(APPROVED_TTL));
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String start(String deviceLabel) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entries.put(token, new Entry(deviceLabel, Status.PENDING, null, Instant.now().plus(PENDING_TTL)));
        return token;
    }

    /** Null if the token doesn't exist or has expired — same "gone, start over" outcome either way. */
    public Entry get(String token) {
        Entry entry = entries.get(token);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(token);
            return null;
        }
        return entry;
    }

    /** False if the token is missing, expired, or already resolved (approved/denied) — a QR code can only ever be used once. */
    public boolean approve(String token, ApprovedResult result) {
        Entry entry = get(token);
        if (entry == null || entry.status() != Status.PENDING) {
            return false;
        }
        entries.put(token, entry.approved(result));
        return true;
    }

    public boolean deny(String token) {
        Entry entry = get(token);
        if (entry == null || entry.status() != Status.PENDING) {
            return false;
        }
        entries.put(token, entry.denied());
        return true;
    }
}
