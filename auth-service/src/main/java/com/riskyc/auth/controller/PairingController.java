package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.PairingService;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WhatsApp-Web-style device linking: a client with no session yet (a web
 * browser, or a phone skipping the OTP SMS) starts a pairing and renders the
 * token as a QR code plus a short hand-typeable code (see PairingService for
 * the in-memory store backing both), then polls status until an
 * already-signed-in device scans or types it in and approves — which is what
 * actually mints the new session here, exactly like a normal OTP login's
 * session-creation (AuthController#verifyOtp) except there's no OTP step,
 * since the approving caller's own valid JWT already proves who's approving.
 * Subject to the same shared device cap as a normal login, and a paired-in
 * session is never master (the approver, by definition already signed in,
 * always has at least one other active session already).
 */
@RestController
@RequestMapping("/api/auth/pairing")
public class PairingController {

    private final PairingService pairingService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final int maxLinkedDevices;

    public PairingController(PairingService pairingService, SessionRepository sessionRepository,
                              UserRepository userRepository, JwtIssuer jwtIssuer,
                              @Value("${riskyc.devices.max-linked:5}") int maxLinkedDevices) {
        this.pairingService = pairingService;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
        this.maxLinkedDevices = maxLinkedDevices;
    }

    public record StartRequest(String deviceLabel, String platform) {
    }

    public record StartResponse(String token, String code) {
    }

    /** No auth required — this is what an anonymous, not-yet-signed-in client calls to get a QR code (and its short manual-entry fallback) in the first place. deviceLabel/platform are client-supplied/display-only, same trust level as OtpVerifyRequest's own (see AuthController's doc comment on that field). */
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest request) {
        String label = request.deviceLabel() != null && !request.deviceLabel().isBlank() ? request.deviceLabel() : "Web browser";
        String platform = request.platform() != null && !request.platform().isBlank() ? request.platform() : "web";
        PairingService.Started started = pairingService.start(label, platform);
        return new StartResponse(started.token(), started.code());
    }

    public record StatusResponse(String status, PairingService.ApprovedResult session) {
    }

    /** Polled by the waiting web client — no auth on this one either, the token itself (32 random bytes) is the only thing that gates it. */
    @GetMapping("/{token}/status")
    public StatusResponse status(@PathVariable String token) {
        PairingService.Entry entry = pairingService.get(token);
        if (entry == null) {
            return new StatusResponse("EXPIRED", null);
        }
        return new StatusResponse(entry.status().name(), entry.status() == PairingService.Status.APPROVED ? entry.result() : null);
    }

    public record InfoResponse(String deviceLabel) {
    }

    /** What the approving device shows before committing — "Link '<deviceLabel>'?" — fetched right after scanning/typing the code in, before calling approve. */
    @GetMapping("/{token}")
    public InfoResponse info(@PathVariable String token) {
        PairingService.Entry entry = pairingService.get(token);
        if (entry == null || entry.status() != PairingService.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.GONE, "This code has expired — please refresh it and try again");
        }
        return new InfoResponse(entry.deviceLabel());
    }

    public record ResolveCodeResponse(String token) {
    }

    /** The manual-entry fallback for when scanning the QR isn't possible — resolves the short, hand-typed code back to its token, then the caller proceeds through the exact same info/approve/deny endpoints above. No auth required, same trust level as every other lookup here. */
    @GetMapping("/by-code/{code}")
    public ResolveCodeResponse byCode(@PathVariable String code) {
        String token = pairingService.resolveCode(code.trim().toUpperCase());
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This code has expired or doesn't exist — please double-check it and try again");
        }
        return new ResolveCodeResponse(token);
    }

    @PostMapping("/{token}/approve")
    public ResponseEntity<Void> approve(@PathVariable String token,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID userId = callerIdFrom(authorization);
        PairingService.Entry entry = pairingService.get(token);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "This code has expired — please refresh it and try again");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));

        List<Session> activeSessions = sessionRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(user.getId());
        if (activeSessions.size() >= maxLinkedDevices) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device limit reached — remove a linked device first");
        }

        String jti = UUID.randomUUID().toString();
        String accessToken = jwtIssuer.issue(user.getId().toString(), Duration.ofDays(30), jti);
        PairingService.ApprovedResult result = new PairingService.ApprovedResult(accessToken, user.getId().toString(),
                user.getDisplayName(), user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber());

        // Approve the pairing entry BEFORE persisting the Session row — if
        // this fails (already used/expired between the get() above and
        // here), the freshly-minted JWT above is simply discarded unsaved
        // rather than leaving an orphaned Session no pairing ever handed
        // out.
        if (!pairingService.approve(token, result)) {
            throw new ResponseStatusException(HttpStatus.GONE, "This code has already been used or expired");
        }
        // isMaster is always false here: approving a pairing requires an
        // already-valid caller session, so this is by construction never
        // the account's first session.
        sessionRepository.save(new Session(jti, user.getId(), entry.deviceLabel(), Instant.now(), entry.platform(), false));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{token}/deny")
    public ResponseEntity<Void> deny(@PathVariable String token,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        callerIdFrom(authorization);
        pairingService.deny(token);
        return ResponseEntity.ok().build();
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
