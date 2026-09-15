package com.riskyc.auth.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one thing standing between /api/auth/otp/request and becoming a free
 * email/SMS bombing tool: without this, anyone can POST any address
 * repeatedly and either spam a real person's inbox/phone or run up SMS
 * provider costs. Two independent limits:
 *   - per identifier: stops hammering ONE victim's address.
 *   - per client IP: stops one attacker cycling through many addresses.
 * In-memory, matching OtpService's own documented MVP-stage stance (fine for
 * one replica; move to Redis with TTL before scaling out) — same tradeoff,
 * same place in the codebase to revisit it.
 */
@Service
public class OtpRateLimiter {

    private static final Duration IDENTIFIER_COOLDOWN = Duration.ofSeconds(45);
    private static final Duration IP_WINDOW = Duration.ofHours(1);
    private static final int IP_MAX_REQUESTS = 12;

    // SMS costs real money per send (unlike email); a deliberately much
    // tighter cap than the general limits above — exactly 2 sends total
    // (the initial "Send code" plus one "Resend") before the client is
    // told to switch to email instead, which has no such cap. Resets after
    // a day rather than being a permanent lockout.
    private static final Duration SMS_TRIAL_WINDOW = Duration.ofHours(24);
    private static final int SMS_MAX_TRIALS = 2;

    private record IpWindow(int count, Instant windowStart) {
    }

    private record TrialWindow(int count, Instant windowStart) {
    }

    private final Map<String, Instant> lastRequestByIdentifier = new ConcurrentHashMap<>();
    private final Map<String, IpWindow> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, TrialWindow> smsTrialsByPhone = new ConcurrentHashMap<>();

    /** @return true if this identifier hasn't requested a code too recently. */
    public boolean allowIdentifier(String identifier) {
        Instant now = Instant.now();
        Instant[] rejected = {null};
        lastRequestByIdentifier.compute(identifier, (key, last) -> {
            if (last != null && now.isBefore(last.plus(IDENTIFIER_COOLDOWN))) {
                rejected[0] = last;
                return last;
            }
            return now;
        });
        return rejected[0] == null;
    }

    /** @return true if this IP hasn't exceeded its hourly request budget. */
    public boolean allowIp(String ip) {
        Instant now = Instant.now();
        IpWindow updated = requestsByIp.compute(ip, (key, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart().plus(IP_WINDOW))) {
                return new IpWindow(1, now);
            }
            return new IpWindow(existing.count() + 1, existing.windowStart());
        });
        return updated.count() <= IP_MAX_REQUESTS;
    }

    /**
     * @return true if this phone number hasn't exceeded its SMS trial cap
     * (2 per 24h) — call once per SMS-channel request, including a resend;
     * never call this for an email-channel request, which has no such cap.
     */
    public boolean allowSmsTrial(String phoneNumber) {
        Instant now = Instant.now();
        TrialWindow updated = smsTrialsByPhone.compute(phoneNumber, (key, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart().plus(SMS_TRIAL_WINDOW))) {
                return new TrialWindow(1, now);
            }
            return new TrialWindow(existing.count() + 1, existing.windowStart());
        });
        return updated.count() <= SMS_MAX_TRIALS;
    }
}
