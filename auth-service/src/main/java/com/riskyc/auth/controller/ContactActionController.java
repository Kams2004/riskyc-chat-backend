package com.riskyc.auth.controller;

import com.riskyc.auth.entity.BlockedUser;
import com.riskyc.auth.entity.Session;
import com.riskyc.auth.entity.UserReport;
import com.riskyc.auth.repository.BlockedUserRepository;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.repository.UserReportRepository;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Block/report a contact — see BlockedUser's own doc comment for the
 * enforcement scope (record + query only, no cross-service message
 * suppression yet).
 */
@RestController
public class ContactActionController {

    private final BlockedUserRepository blockedUserRepository;
    private final UserReportRepository userReportRepository;
    private final JwtIssuer jwtIssuer;
    private final SessionRepository sessionRepository;

    public ContactActionController(BlockedUserRepository blockedUserRepository, UserReportRepository userReportRepository,
                                    JwtIssuer jwtIssuer, SessionRepository sessionRepository) {
        this.blockedUserRepository = blockedUserRepository;
        this.userReportRepository = userReportRepository;
        this.jwtIssuer = jwtIssuer;
        this.sessionRepository = sessionRepository;
    }

    public record BlockedUserResult(String userId) {
    }

    public record ReportRequest(String reason) {
    }

    @GetMapping("/api/users/me/blocked")
    public List<BlockedUserResult> listBlocked(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID callerId = callerIdFrom(authorization);
        return blockedUserRepository.findByBlockerId(callerId).stream()
                .map(b -> new BlockedUserResult(b.getBlockedId().toString()))
                .toList();
    }

    @PostMapping("/api/users/me/blocked/{userId}")
    public void block(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID userId) {
        UUID callerId = callerIdFrom(authorization);
        if (callerId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot block yourself");
        }
        if (!blockedUserRepository.existsByBlockerIdAndBlockedId(callerId, userId)) {
            blockedUserRepository.save(new BlockedUser(callerId, userId, Instant.now()));
        }
    }

    @DeleteMapping("/api/users/me/blocked/{userId}")
    public void unblock(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID userId) {
        UUID callerId = callerIdFrom(authorization);
        blockedUserRepository.deleteByBlockerIdAndBlockedId(callerId, userId);
    }

    @PostMapping("/api/users/{userId}/report")
    public void report(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID userId,
                        @RequestBody ReportRequest request) {
        UUID callerId = callerIdFrom(authorization);
        userReportRepository.save(new UserReport(callerId, userId, request.reason(), Instant.now()));
    }

    private UUID callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            boolean revoked = sessionRepository.findByJti(claims.jti()).map(Session::isRevoked).orElse(false);
            if (revoked) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return UUID.fromString(claims.subject());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
