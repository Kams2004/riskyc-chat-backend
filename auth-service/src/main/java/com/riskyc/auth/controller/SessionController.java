package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.service.SessionService;
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
    private final SessionService sessionService;
    private final JwtIssuer jwtIssuer;
    private final String internalApiKey;
    private final int maxLinkedDevices;

    public SessionController(SessionRepository sessionRepository, SessionService sessionService, JwtIssuer jwtIssuer,
                              @Value("${riskyc.internal.api-key:}") String internalApiKey,
                              @Value("${riskyc.devices.max-linked:5}") int maxLinkedDevices) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.jwtIssuer = jwtIssuer;
        this.internalApiKey = internalApiKey;
        this.maxLinkedDevices = maxLinkedDevices;
    }

    public record SessionResult(String id, String deviceLabel, String createdAt, String lastSeenAt, boolean isCurrent, boolean isMaster) {
    }

    @GetMapping("/api/sessions")
    public List<SessionResult> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        JwtIssuer.JwtClaims claims = claimsFrom(authorization);
        UUID userId = UUID.fromString(claims.subject());
        return sessionRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(userId).stream()
                .map(s -> new SessionResult(s.getId().toString(), s.getDeviceLabel(), s.getCreatedAt().toString(),
                        s.getLastSeenAt().toString(), s.getJti().equals(claims.jti()), s.isMaster()))
                .toList();
    }

    public record DeviceLimitResponse(int maxLinkedDevices) {
    }

    /** The configured device cap — a deployment-wide constant, not per-user state, so this is a cheap standalone call rather than repeating the value on every row of list() above. */
    @GetMapping("/api/sessions/limit")
    public DeviceLimitResponse limit() {
        return new DeviceLimitResponse(maxLinkedDevices);
    }

    /**
     * Revoking your OWN current session is always allowed to anyone — an
     * ordinary sign-out. Revoking any OTHER device requires the caller's own
     * session to be master; a non-master caller gets 403. If the caller IS
     * master and is revoking their OWN session, that's a master sign-out —
     * which cascades to revoke every other linked device too (see
     * SessionService#revokeAll), rather than leaving the account's other
     * devices live with no master at all. The account isn't left masterless
     * for long: the next login re-earns master (AuthController#verifyOtp).
     */
    @DeleteMapping("/api/sessions/{id}")
    public void revoke(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        JwtIssuer.JwtClaims claims = claimsFrom(authorization);
        UUID userId = UUID.fromString(claims.subject());
        Session callerSession = sessionRepository.findByJti(claims.jti())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out"));

        Session target = sessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session"));
        if (!target.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your session");
        }

        boolean isOwnSession = target.getId().equals(callerSession.getId());
        if (!isOwnSession && !callerSession.isMaster()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the master device can remove other devices");
        }

        if (isOwnSession && callerSession.isMaster()) {
            sessionService.revokeAll(userId);
            return;
        }

        target.revoke(Instant.now());
        sessionRepository.save(target);
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
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            // Was missing entirely despite this class's own doc comment
            // claiming auth-service enforces revocation — a signed-out
            // session's still-valid-looking JWT could list/revoke other
            // sessions here. Same check as UserController#callerIdFrom.
            boolean revoked = sessionRepository.findByJti(claims.jti()).map(Session::isRevoked).orElse(false);
            if (revoked) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
