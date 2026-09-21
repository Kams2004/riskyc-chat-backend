package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.entity.PushToken;
import com.riskyc.messaging.repository.PushTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final String internalApiKey;

    public PushTokenController(PushTokenRepository pushTokenRepository, JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache,
                                @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.pushTokenRepository = pushTokenRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
        this.internalApiKey = internalApiKey;
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

    public record UnregisterRequest(String token) {
    }

    /**
     * Sign-out and delete-account both call this for the CURRENT device's
     * own token before clearing local session state — without it, a token
     * row just sits there forever pointing at an account nobody's signed
     * into anymore, and PushNotificationService.sendToUser has no way to
     * know that. Scoped to tokens owned by the caller (defensive: stops
     * one user from unregistering another's device) and to the ONE token
     * given, not every token this user has registered elsewhere — signing
     * out on one device must never silently kill notifications on another
     * still-signed-in one.
     */
    @DeleteMapping
    public ResponseEntity<Void> unregister(@RequestBody UnregisterRequest request,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        pushTokenRepository.findById(request.token())
                .filter(t -> t.getUserId().equals(userId))
                .ifPresent(pushTokenRepository::delete);
        return ResponseEntity.ok().build();
    }

    /**
     * Called by auth-service right after deleting an account (see
     * UserController#deleteMe there) — same shared-secret pattern as
     * SystemAccountController's own internal endpoints. Unlike the
     * client-facing DELETE above, this removes EVERY token this user ever
     * registered, on every device, since a deleted account has no "other
     * still-signed-in device" to preserve notifications for.
     */
    @DeleteMapping("/internal/push-tokens/{userId}")
    public ResponseEntity<Void> unregisterAllForUser(@PathVariable String userId,
                                                       @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
        pushTokenRepository.deleteByUserId(userId);
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
