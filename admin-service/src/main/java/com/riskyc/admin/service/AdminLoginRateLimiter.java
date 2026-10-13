package com.riskyc.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blunts brute-force attempts against POST /api/admin/auth/login — a fixed
 * per-IP window, same in-memory MVP tradeoff (fine for one replica, revisit
 * with Redis before scaling out) as auth-service's OtpRateLimiter, which
 * this mirrors.
 */
@Service
public class AdminLoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;

    public AdminLoginRateLimiter(@Value("${riskyc.admin.login-max-attempts:5}") int maxAttempts,
                                  @Value("${riskyc.admin.login-window-minutes:15}") long windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    private record AttemptWindow(int count, Instant windowStart) {
    }

    private final Map<String, AttemptWindow> attemptsByIp = new ConcurrentHashMap<>();

    /** @return true if this IP hasn't exceeded its login-attempt budget for the current window. Call once per attempt, success or failure. */
    public boolean allowIp(String ip) {
        Instant now = Instant.now();
        AttemptWindow updated = attemptsByIp.compute(ip, (key, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart().plus(window))) {
                return new AttemptWindow(1, now);
            }
            return new AttemptWindow(existing.count() + 1, existing.windowStart());
        });
        return updated.count() <= maxAttempts;
    }

    /** How long until this IP's window resets, for a precise 429 retry-after. */
    public Duration resetIn(String ip) {
        AttemptWindow existing = attemptsByIp.get(ip);
        if (existing == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(Instant.now(), existing.windowStart().plus(window));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
