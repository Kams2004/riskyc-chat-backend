package com.riskyc.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

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

    public String issue(String subjectUserId, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subjectUserId)
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
}
