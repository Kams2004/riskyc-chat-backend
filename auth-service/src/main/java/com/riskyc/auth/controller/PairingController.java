package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.PairingService;
import com.riskyc.common.security.JwtIssuer;
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
import java.util.UUID;

/**
 * WhatsApp-Web-style QR device linking: a web client that has no session
 * yet starts a pairing and renders the token as a QR code (see
 * PairingService for the in-memory store backing this), then polls status
 * until an already-signed-in MOBILE app scans it and approves — which is
 * what actually mints the new web session here, exactly like a normal OTP
 * login's session-creation (AuthController#verifyOtp) except there's no
 * OTP step, since the mobile caller's own valid JWT already proves who's
 * approving. No changes needed to the one-active-mobile-session rule or
 * the Session/JWT model — a linked web session already coexists with a
 * mobile one today by design (see AuthController#MOBILE_PLATFORM).
 */
@RestController
@RequestMapping("/api/auth/pairing")
public class PairingController {

    private final PairingService pairingService;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;

    public PairingController(PairingService pairingService, SessionRepository sessionRepository,
                              UserRepository userRepository, JwtIssuer jwtIssuer) {
        this.pairingService = pairingService;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    public record StartRequest(String deviceLabel) {
    }

    public record StartResponse(String token) {
    }

    /** No auth required — this is what an anonymous, not-yet-signed-in web client calls to get a QR code in the first place. deviceLabel is client-supplied/display-only, same trust level as OtpVerifyRequest's own (see AuthController's doc comment on that field). */
    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest request) {
        String label = request.deviceLabel() != null && !request.deviceLabel().isBlank() ? request.deviceLabel() : "Web browser";
        return new StartResponse(pairingService.start(label));
    }

    public record StatusResponse(String status, PairingService.ApprovedResult session) {
    }

    /** Polled by the waiting web client — no auth on this one either, the token itself (32 random bytes) is the only thing that gates it, same trust model as the confirmationToken in DeviceSwitchService. */
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

    /** What the mobile scanner shows before committing — "Link '<deviceLabel>'?" — fetched right after scanning, before calling approve. */
    @GetMapping("/{token}")
    public InfoResponse info(@PathVariable String token) {
        PairingService.Entry entry = pairingService.get(token);
        if (entry == null || entry.status() != PairingService.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.GONE, "This QR code has expired — please refresh it and scan again");
        }
        return new InfoResponse(entry.deviceLabel());
    }

    @PostMapping("/{token}/approve")
    public ResponseEntity<Void> approve(@PathVariable String token,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID userId = callerIdFrom(authorization);
        PairingService.Entry entry = pairingService.get(token);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "This QR code has expired — please refresh it and scan again");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));

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
            throw new ResponseStatusException(HttpStatus.GONE, "This QR code has already been used or expired");
        }
        sessionRepository.save(new Session(jti, user.getId(), entry.deviceLabel(), Instant.now(), "web"));
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
