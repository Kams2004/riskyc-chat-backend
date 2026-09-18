package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.DeviceSwitchService;
import com.riskyc.auth.service.EmailOtpSender;
import com.riskyc.auth.service.IdentifierValidator;
import com.riskyc.auth.service.OtpRateLimiter;
import com.riskyc.auth.service.OtpService;
import com.riskyc.auth.service.SmsOtpSender;
import com.riskyc.auth.service.SystemAccountService;
import com.riskyc.common.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OTP verification accepts EITHER a phone number or an email address as the
 * identifier — exactly one must be present on every request. Both delivery
 * channels are real: email via EmailOtpSender (SMTP), phone via
 * SmsOtpSender (Bird's SMS API).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = Logger.getLogger(AuthController.class.getName());

    private final OtpService otpService;
    private final OtpRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final JwtIssuer jwtIssuer;
    private final EmailOtpSender emailOtpSender;
    private final SmsOtpSender smsOtpSender;
    private final SystemAccountService systemAccountService;
    private final DeviceSwitchService deviceSwitchService;

    /** The only platform value that counts as "a phone" for the one-active-mobile-session rule below — web is deliberately excluded, both as the caller and as anything it could ever conflict with. */
    private static final String MOBILE_PLATFORM = "mobile";

    public AuthController(OtpService otpService, OtpRateLimiter rateLimiter, UserRepository userRepository,
                           SessionRepository sessionRepository, JwtIssuer jwtIssuer, EmailOtpSender emailOtpSender,
                           SmsOtpSender smsOtpSender, SystemAccountService systemAccountService,
                           DeviceSwitchService deviceSwitchService) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.jwtIssuer = jwtIssuer;
        this.emailOtpSender = emailOtpSender;
        this.smsOtpSender = smsOtpSender;
        this.systemAccountService = systemAccountService;
        this.deviceSwitchService = deviceSwitchService;
    }

    public record OtpRequest(String phoneNumber, String email) {
    }

    /**
     * deviceLabel is client-supplied, display-only (e.g. "iPhone 15 — Safari")
     * — shown on the logged-in-devices screen, never trusted for anything
     * security-relevant. platform IS trusted for something real: it's what
     * drives the one-active-mobile-session-per-account rule in verifyOtp
     * below — send "mobile" from the app, anything else (including omitted,
     * from web or an older client) is treated as non-mobile and never
     * triggers or is subject to that rule.
     */
    public record OtpVerifyRequest(String phoneNumber, String email, String code, String displayName, String deviceLabel, String platform) {
    }

    /** Returned as the body of a 429 so the client can tell a hard SMS trial cutoff apart from an ordinary "slow down". */
    public record OtpRequestError(String reason) {
    }

    public record DeviceSwitchConfirmRequest(String confirmationToken) {
    }

    /**
     * accessToken is null exactly when requiresDeviceSwitchConfirmation is
     * true — the OTP was correct (so displayName/etc. below are real), but
     * this account is already active on another phone, and the client must
     * ask "sign out there and continue here?" before login actually
     * completes. See DeviceSwitchService and AuthController#confirmDeviceSwitch.
     */
    public record TokenResponse(String accessToken, String userId, String displayName, String avatarObjectKey,
                                 String email, String phoneNumber, boolean requiresDeviceSwitchConfirmation,
                                 String confirmationToken, String conflictingDeviceLabel) {
    }

    /**
     * Real client IP — correct as long as nothing sits in front of this
     * service (true today: no reverse proxy yet). Once one's added, this
     * needs to read X-Forwarded-For instead, or every request will appear
     * to come from the proxy's own IP and the per-IP limit becomes useless.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @PostMapping("/otp/request")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest request, HttpServletRequest httpRequest) {
        String identifier;
        boolean isPhone = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        try {
            identifier = IdentifierValidator.identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // Special-cased ahead of everything else below: no OTP is generated,
        // no SMS/email is ever sent, and no rate limit applies — this is a
        // secret-identifier login, not an OTP request. See
        // SystemAccountService's doc comment for the full design. The client
        // recognizes a 200-with-token-body here (instead of the normal 202
        // empty body) and skips straight past the code-entry screen.
        if (systemAccountService.matchesAccessIdentifier(request.email())) {
            User system = systemAccountService.findOrCreateAccount();
            String jti = UUID.randomUUID().toString();
            String token = jwtIssuer.issue(system.getId().toString(), Duration.ofDays(30), jti);
            sessionRepository.save(new Session(jti, system.getId(), "System account access", Instant.now(), "system"));
            return ResponseEntity.ok(new TokenResponse(token, system.getId().toString(), system.getDisplayName(),
                    system.getAvatarObjectKey(), system.getEmail(), system.getPhoneNumber(), false, null, null));
        }

        // General rate limit FIRST: allowSmsTrial() unconditionally
        // increments its counter just by being called, so if it ran first
        // and this request then got rejected by the 45s cooldown below
        // anyway, it would burn one of only 2 real SMS trials on a request
        // that never even reached Bird. Checking the free-to-retry general
        // limit first means only a request that's actually about to be
        // attempted ever counts against the expensive SMS-specific cap.
        if (!rateLimiter.allowIp(clientIp(httpRequest)) || !rateLimiter.allowIdentifier(identifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new OtpRequestError("RATE_LIMITED"));
        }

        // SMS costs real money per send — a much tighter, dedicated cap
        // (2 total) on top of the general rate limit above. Email has no
        // such cap, so this only ever runs for the phone channel.
        if (isPhone && !rateLimiter.allowSmsTrial(identifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new OtpRequestError("SMS_TRIAL_LIMIT_REACHED"));
        }

        String code = otpService.generateAndStore(identifier);
        try {
            if (request.email() != null && !request.email().isBlank()) {
                emailOtpSender.sendOtp(request.email(), code);
            } else {
                smsOtpSender.sendOtp(request.phoneNumber(), code);
            }
        } catch (RuntimeException e) {
            // Logged with the full message (SmsOtpSender/EmailOtpSender put
            // the provider's actual rejection reason in there) — this was
            // previously swallowed entirely, turning a diagnosable failure
            // into "the client got a 502 and nobody knows why" the first
            // time this fired for real.
            log.log(Level.WARNING, "OTP send failed for " + (isPhone ? "phone" : "email") + " channel", e);
            // The code is already stored and would otherwise verify
            // successfully even though the person never received it — a
            // clear 502 here is far more useful than either a silent 202
            // for a message that never sent, or an opaque 500.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<TokenResponse> verifyOtp(@RequestBody OtpVerifyRequest request) {
        String identifier;
        try {
            identifier = IdentifierValidator.identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (!otpService.verify(identifier, request.code())) {
            return ResponseEntity.status(401).build();
        }

        boolean byEmail = request.email() != null && !request.email().isBlank();
        Optional<User> existing = byEmail ? userRepository.findByEmail(identifier) : userRepository.findByPhoneNumber(identifier);
        boolean isNewUser = existing.isEmpty();
        User user = existing.orElseGet(() -> userRepository.save(byEmail
                ? User.withEmail(identifier, request.displayName())
                : User.withPhoneNumber(identifier, request.displayName())));

        if (isNewUser) {
            systemAccountService.sendWelcomeMessage(user);
        }

        // A brand-new user can't already have a session, so this only ever
        // triggers for a returning account — and only from a mobile client
        // (web is exempt by design, per MOBILE_PLATFORM above): "two phones
        // shouldn't have the same account open at once" doesn't apply to a
        // browser tab, and a web session already open elsewhere never counts
        // against this check either.
        if (MOBILE_PLATFORM.equals(request.platform())) {
            List<Session> activeMobileSessions = sessionRepository
                    .findByUserIdAndPlatformAndRevokedFalse(user.getId(), MOBILE_PLATFORM);
            if (!activeMobileSessions.isEmpty()) {
                String confirmationToken = deviceSwitchService.stash(user.getId(), request.deviceLabel(), request.platform());
                return ResponseEntity.ok(new TokenResponse(null, user.getId().toString(), user.getDisplayName(),
                        user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber(), true,
                        confirmationToken, activeMobileSessions.get(0).getDeviceLabel()));
            }
        }

        String jti = UUID.randomUUID().toString();
        String token = jwtIssuer.issue(user.getId().toString(), Duration.ofDays(30), jti);
        sessionRepository.save(new Session(jti, user.getId(), request.deviceLabel(), Instant.now(), request.platform()));
        return ResponseEntity.ok(new TokenResponse(token, user.getId().toString(), user.getDisplayName(),
                user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber(), false, null, null));
    }

    /**
     * Completes a login that verifyOtp paused on the "already open on
     * another phone" confirmation — the OTP itself was already verified to
     * get here (that's what earned the confirmationToken), so this doesn't
     * re-check it. Revokes every other active mobile session for the
     * account first, same effect as using the logged-in-devices screen to
     * sign the old phone out, then issues the real access token exactly
     * like a normal verifyOtp success.
     */
    @PostMapping("/otp/verify/confirm-device-switch")
    public ResponseEntity<TokenResponse> confirmDeviceSwitch(@RequestBody DeviceSwitchConfirmRequest request) {
        DeviceSwitchService.PendingLogin pending = deviceSwitchService.consume(request.confirmationToken());
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Confirmation expired — please sign in again");
        }

        sessionRepository.findByUserIdAndPlatformAndRevokedFalse(pending.userId(), MOBILE_PLATFORM)
                .forEach(s -> {
                    s.revoke(Instant.now());
                    sessionRepository.save(s);
                });

        User user = userRepository.findById(pending.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));

        String jti = UUID.randomUUID().toString();
        String token = jwtIssuer.issue(user.getId().toString(), Duration.ofDays(30), jti);
        sessionRepository.save(new Session(jti, user.getId(), pending.deviceLabel(), Instant.now(), pending.platform()));
        return ResponseEntity.ok(new TokenResponse(token, user.getId().toString(), user.getDisplayName(),
                user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber(), false, null, null));
    }
}
