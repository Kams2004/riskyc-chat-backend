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

    private record OtpEntry(String code, Instant expiresAt) {
    }

    private final Map<String, OtpEntry> otpsByPhoneNumber = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String generateAndStore(String phoneNumber) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        otpsByPhoneNumber.put(phoneNumber, new OtpEntry(code, Instant.now().plusSeconds(300)));
        return code;
    }

    public boolean verify(String phoneNumber, String code) {
        OtpEntry entry = otpsByPhoneNumber.get(phoneNumber);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return false;
        }
        boolean matches = entry.code().equals(code);
        if (matches) {
            otpsByPhoneNumber.remove(phoneNumber);
        }
        return matches;
    }
}
