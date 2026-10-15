package com.riskyc.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs the QR "link another device without an OTP" flow — WhatsApp Web-
 * style, scoped to a single auth-service instance for now (in-memory MVP
 * tradeoff; move to Redis before running more than one replica). The new
 * device (a web browser, or a phone that doesn't want to spend an OTP SMS)
 * starts a pairing and renders the returned token as a QR code (plus the
 * short code as a type-it-in fallback for when a camera can't be used), then
 * polls status; an already-signed-in device scans or types it in and
 * approves, which is what actually mints the new session/JWT (the approver
 * only ever proves ITS OWN identity — the new session it's creating is a
 * side effect of that, never something the approver receives or handles
 * itself).
 */
@Service
public class PairingService {

    private static final Duration PENDING_TTL = Duration.ofMinutes(3);
    // Short — this only needs to survive until the waiting client's next poll
    // picks up the result, not stay valid indefinitely once issued.
    private static final Duration APPROVED_TTL = Duration.ofSeconds(30);

    // Excludes 0/O/1/I — ambiguous when hand-typed from one device's screen
    // onto another's keyboard. 32 symbols ^ 8 chars ≈ 1.1e12 combinations,
    // which next to the 3-minute PENDING_TTL is safe against guessing without
    // needing a dedicated rate limiter on the lookup endpoint.
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    public enum Status { PENDING, APPROVED, DENIED }

    public record ApprovedResult(String accessToken, String userId, String displayName, String avatarObjectKey,
                                  String email, String phoneNumber) {
    }

    public record Entry(String deviceLabel, String platform, String code, Status status, ApprovedResult result,
                         Instant expiresAt) {
        Entry approved(ApprovedResult result) {
            return new Entry(deviceLabel, platform, code, Status.APPROVED, result, Instant.now().plus(APPROVED_TTL));
        }

        Entry denied() {
            return new Entry(deviceLabel, platform, code, Status.DENIED, null, Instant.now().plus(APPROVED_TTL));
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> codeToToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public record Started(String token, String code) {
    }

    public Started start(String deviceLabel, String platform) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String code = generateCode();
        entries.put(token, new Entry(deviceLabel, platform, code, Status.PENDING, null, Instant.now().plus(PENDING_TTL)));
        codeToToken.put(code, token);
        return new Started(token, code);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Null if the token doesn't exist or has expired — same "gone, start over" outcome either way. */
    public Entry get(String token) {
        Entry entry = entries.get(token);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(token);
            codeToToken.remove(entry.code());
            return null;
        }
        return entry;
    }

    /**
     * Resolves the short, hand-typeable form of a token (see start()) — the
     * manual-entry fallback for when scanning the QR isn't possible. Null
     * under the same "gone, start over" conditions as get(token).
     */
    public String resolveCode(String code) {
        String token = codeToToken.get(code);
        if (token == null) {
            return null;
        }
        // get() purges both maps together once expired, so a hit here always
        // means the token is still live — but run it through get() anyway to
        // pick up that purge on the expiry path too.
        return get(token) != null ? token : null;
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
