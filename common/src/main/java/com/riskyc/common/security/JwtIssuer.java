package com.riskyc.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies the JWTs every service trusts for authenticated requests.
 * All services must share the same signing secret (via config), since auth-service
 * issues tokens that messaging/media/presence-service verify independently.
 */
public class JwtIssuer {

    private final SecretKey signingKey;

    public JwtIssuer(String base64Secret) {
        this.signingKey = Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(base64Secret));
    }

    /** Convenience overload for call sites that don't need to track the session (e.g. tests) — generates a random jti. */
    public String issue(String subjectUserId, Duration ttl) {
        return issue(subjectUserId, ttl, UUID.randomUUID().toString());
    }

    /**
     * jti (JWT ID) is what lets a token be individually revoked later — see
     * auth-service's Session entity and messaging-service's RevokedJtiCache.
     * Without it, the only way to invalidate a leaked/signed-out token would
     * be rotating the shared signing secret, which invalidates every token
     * for every user at once.
     */
    public String issue(String subjectUserId, Duration ttl, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subjectUserId)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(signingKey)
                .compact();
    }

    public String verifyAndGetSubject(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /** record, not two separate calls — parses the token once instead of twice for callers that need both fields. */
    public record JwtClaims(String subject, String jti) {
    }

    public JwtClaims verifyAndGetClaims(String token) {
        var payload = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtClaims(payload.getSubject(), payload.getId());
    }
}
