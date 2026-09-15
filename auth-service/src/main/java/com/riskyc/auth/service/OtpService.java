package com.riskyc.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory OTP store for the MVP. Replace with Redis (shared across instances,
 * with TTL) before running more than one auth-service replica, and wire up a
 * real SMS provider instead of logging the code.
 */
@Service
public class OtpService {

    // A 6-digit code is only as safe as the number of guesses an attacker
    // gets against it — with no cap, someone could brute-force the full
    // 1,000,000-value space well within the 5-minute expiry just by firing
    // requests rapidly. Burning the code after this many wrong guesses
    // closes that off entirely (correct on any attempt still consumes it,
    // same as before).
    private static final int MAX_ATTEMPTS = 5;

    private record OtpEntry(String code, Instant expiresAt, int attempts) {
    }

    private final Map<String, OtpEntry> otpsByPhoneNumber = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String generateAndStore(String phoneNumber) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        otpsByPhoneNumber.put(phoneNumber, new OtpEntry(code, Instant.now().plusSeconds(300), 0));
        return code;
    }

    public boolean verify(String phoneNumber, String code) {
        OtpEntry entry = otpsByPhoneNumber.get(phoneNumber);
        if (entry == null || Instant.now().isAfter(entry.expiresAt()) || entry.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        boolean matches = entry.code().equals(code);
        if (matches) {
            otpsByPhoneNumber.remove(phoneNumber);
        } else {
            otpsByPhoneNumber.put(phoneNumber, new OtpEntry(entry.code(), entry.expiresAt(), entry.attempts() + 1));
        }
        return matches;
    }
}
