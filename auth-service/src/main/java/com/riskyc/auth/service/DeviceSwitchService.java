package com.riskyc.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a login that's fully OTP-verified but paused on a "this account is
 * already open on another phone — sign out there and continue here?"
 * confirmation (see AuthController#verifyOtp / #confirmDeviceSwitch). The
 * opaque token handed to the client is NOT a JWT — it only unlocks the one
 * confirm endpoint, unlike a real access token it can't authenticate any
 * other request, so a 5-minute in-memory lifetime here is plenty safe.
 * Same in-memory/single-instance MVP tradeoff as OtpService — move to Redis
 * before running more than one auth-service replica.
 */
@Service
public class DeviceSwitchService {

    private static final Duration TTL = Duration.ofMinutes(5);

    public record PendingLogin(UUID userId, String deviceLabel, String platform, Instant expiresAt) {
    }

    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String stash(UUID userId, String deviceLabel, String platform) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(token, new PendingLogin(userId, deviceLabel, platform, Instant.now().plus(TTL)));
        return token;
    }

    /** Single-use: a confirmation can only ever complete the one login it was issued for. */
    public PendingLogin consume(String token) {
        PendingLogin entry = pending.remove(token);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return null;
        }
        return entry;
    }
}
