package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.repository.CallRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/calls")
public class CallHistoryController {

    private final CallRepository callRepository;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public CallHistoryController(CallRepository callRepository, JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.callRepository = callRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record CallResult(String id, String callerId, String calleeId, String type, String status,
                              Instant startedAt, Instant answeredAt, Instant endedAt,
                              Long callerBytesSent, Long callerBytesReceived,
                              Long calleeBytesSent, Long calleeBytesReceived) {
    }

    @GetMapping
    public List<CallResult> myCalls(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        return callRepository.findAllForUser(callerId).stream().map(this::toResult).toList();
    }

    private CallResult toResult(Call call) {
        return new CallResult(call.getId(), call.getCallerId(), call.getCalleeId(), call.getType().name(),
                call.getStatus().name(), call.getStartedAt(), call.getAnsweredAt(), call.getEndedAt(),
                call.getCallerBytesSent(), call.getCallerBytesReceived(),
                call.getCalleeBytesSent(), call.getCalleeBytesReceived());
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
