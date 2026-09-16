package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Logged-in devices" — list/revoke the caller's own sessions, plus an
 * internal endpoint messaging-service's RevokedJtiCache polls (see that
 * class). See Session's own doc comment for the scope this enforces:
 * auth-service (here) and messaging-service (via the poller) trust
 * revocation; media-service/presence-service don't check it at all — both
 * are Postgres-free and this MVP doesn't add a datasource to either purely
 * for that, a tradeoff documented there too.
 */
@RestController
public class SessionController {

    private final SessionRepository sessionRepository;
    private final JwtIssuer jwtIssuer;
    private final String internalApiKey;

    public SessionController(SessionRepository sessionRepository, JwtIssuer jwtIssuer,
                              @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.sessionRepository = sessionRepository;
        this.jwtIssuer = jwtIssuer;
        this.internalApiKey = internalApiKey;
    }

    public record SessionResult(String id, String deviceLabel, String createdAt, String lastSeenAt, boolean isCurrent) {
    }

    @GetMapping("/api/sessions")
    public List<SessionResult> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        JwtIssuer.JwtClaims claims = claimsFrom(authorization);
        UUID userId = UUID.fromString(claims.subject());
        return sessionRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(userId).stream()
                .map(s -> new SessionResult(s.getId().toString(), s.getDeviceLabel(), s.getCreatedAt().toString(),
                        s.getLastSeenAt().toString(), s.getJti().equals(claims.jti())))
                .toList();
    }

    /** Signing out ANY session — including the caller's own current one — is allowed; that's just an ordinary sign-out. */
    @DeleteMapping("/api/sessions/{id}")
    public void revoke(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        JwtIssuer.JwtClaims claims = claimsFrom(authorization);
        UUID userId = UUID.fromString(claims.subject());
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your session");
        }
        session.revoke(Instant.now());
        sessionRepository.save(session);
    }

    /**
     * Shared-secret protected, not JWT-protected — this is service-to-service
     * traffic (messaging-service's poller), not a user request. Simple
     * static key over the internal docker network, matching this codebase's
     * existing "adequate for a pre-hardening MVP" stance elsewhere (e.g.
     * coturn's static TURN credential) — blank key (the default) disables
     * the check for local dev, same blank-default pattern as the SMS/mail
     * credentials.
     */
    @GetMapping("/internal/sessions/revoked-jtis")
    public List<String> revokedJtis(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                     @RequestParam String since) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
        Instant sinceInstant = Instant.parse(since);
        return sessionRepository.findByRevokedTrueAndRevokedAtAfter(sinceInstant).stream()
                .map(Session::getJti)
                .toList();
    }

    private JwtIssuer.JwtClaims claimsFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            return jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
