package com.riskyc.admin.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin "logout" support — a revoked jti is rejected by adminCallerFrom even
 * though its JWT signature is still technically valid until natural
 * expiry, same idea as auth-service's Session.revoke()/RevokedJtiCache, just
 * in-memory and admin-service-local (single instance, lost on restart —
 * acceptable for a 12h-TTL admin token on a non-clustered service, same
 * documented MVP tradeoff class as OtpRateLimiter).
 */
@Service
public class AdminJwtRevocationStore {

    private final Map<String, Instant> revokedUntil = new ConcurrentHashMap<>();

    /** @param tokenExpiry when the JWT itself would have naturally expired — once past that, there's no reason to keep remembering the revocation. */
    public void revoke(String jti, Instant tokenExpiry) {
        revokedUntil.put(jti, tokenExpiry);
        // Lazy sweep on every insert, rather than a scheduled job — bounded
        // by how often admins log out, which is rare for a 12h-TTL token.
        Instant now = Instant.now();
        revokedUntil.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    public boolean isRevoked(String jti) {
        return revokedUntil.containsKey(jti);
    }
}
