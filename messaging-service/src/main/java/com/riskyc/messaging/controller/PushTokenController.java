package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.entity.PushToken;
import com.riskyc.messaging.repository.PushTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/push-tokens")
public class PushTokenController {

    private final PushTokenRepository pushTokenRepository;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public PushTokenController(PushTokenRepository pushTokenRepository, JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.pushTokenRepository = pushTokenRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record RegisterRequest(String token, String platform) {
    }

    /** Idempotent — the mobile client calls this on every sign-in, re-registering the same token is a no-op update. */
    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        PushToken existing = pushTokenRepository.findById(request.token())
                .orElseGet(() -> new PushToken(request.token(), userId, request.platform(), Instant.now()));
        existing.setUserId(userId);
        existing.setPlatform(request.platform());
        existing.setUpdatedAt(Instant.now());
        pushTokenRepository.save(existing);
        return ResponseEntity.ok().build();
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revokedJtiCache.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims.subject();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
