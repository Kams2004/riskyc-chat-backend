package com.riskyc.auth.controller;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.SessionRepository;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.EmailOtpSender;
import com.riskyc.auth.service.IdentifierValidator;
import com.riskyc.auth.service.OtpRateLimiter;
import com.riskyc.auth.service.OtpService;
import com.riskyc.auth.service.SmsOtpSender;
import com.riskyc.auth.service.SystemAccountService;
import com.riskyc.common.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final int maxLinkedDevices;

    public AuthController(OtpService otpService, OtpRateLimiter rateLimiter, UserRepository userRepository,
                           SessionRepository sessionRepository, JwtIssuer jwtIssuer, EmailOtpSender emailOtpSender,
                           SmsOtpSender smsOtpSender, SystemAccountService systemAccountService,
                           @Value("${riskyc.devices.max-linked:5}") int maxLinkedDevices) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.jwtIssuer = jwtIssuer;
        this.emailOtpSender = emailOtpSender;
        this.smsOtpSender = smsOtpSender;
        this.systemAccountService = systemAccountService;
        this.maxLinkedDevices = maxLinkedDevices;
    }

    public record OtpRequest(String phoneNumber, String email) {
    }

    /**
     * deviceLabel is client-supplied, display-only (e.g. "iPhone 15 — Safari")
     * — shown on the logged-in-devices screen, never trusted for anything
     * security-relevant. platform is stored alongside it for the same
     * display purpose; it no longer gates any server-side rule (any number
     * of devices on any mix of platforms may be linked at once, up to the
     * shared device cap — see verifyOtp).
     */
    public record OtpVerifyRequest(String phoneNumber, String email, String code, String displayName, String deviceLabel, String platform) {
    }

    /**
     * Returned as the body of a 429 so the client can tell a hard SMS trial
     * cutoff apart from an ordinary "slow down". retryAfterSeconds is only
     * populated for SMS_TRIAL_LIMIT_REACHED — an exact count-down to when
     * this specific number's 24h window resets, rather than a vague
     * "try again later" with no actual time attached.
     */
    public record OtpRequestError(String reason, Long retryAfterSeconds) {
    }

    /** Returned as the body of a 403 when this account already has maxLinkedDevices active sessions — the master device must remove one (see SessionController#revoke) before another device can log in. */
    public record DeviceLimitError(String reason, int maxLinkedDevices) {
    }

    /** Returned as the body of a 403 when an admin has suspended this account (see admin-service). message is the admin-supplied reason, if any. */
    public record SuspendedError(String reason, String message) {
    }

    public record TokenResponse(String accessToken, String userId, String displayName, String avatarObjectKey,
                                 String email, String phoneNumber) {
    }

    /**
     * chat.riskycfashion.com's nginx now sits in front of this service (see
     * nginx-chat.riskycfashion.com.conf's /auth/ location), so
     * getRemoteAddr() alone would return NGINX'S OWN IP for every request —
     * exactly the failure this comment used to warn about before it
     * happened: the per-IP rate limit below silently became one single
     * shared budget for every user going through the proxy at once,
     * unrelated phone numbers and even the email channel included, since
     * this check runs before the phone/email split.
     *
     * nginx's `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`
     * APPENDS the real client IP after whatever a client already sent (if
     * anything) — so the real IP is the LAST entry, never the first. Taking
     * the first entry instead would trust a value the client itself
     * controls, letting anyone bypass this limiter by just sending a fresh
     * fake X-Forwarded-For on every request.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
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
            // Always treated as master and exempt from the device cap — this
            // is a singleton administrative login path, not a normal
            // multi-device user account.
            sessionRepository.save(new Session(jti, system.getId(), "System account access", Instant.now(), "system", true));
            return ResponseEntity.ok(new TokenResponse(token, system.getId().toString(), system.getDisplayName(),
                    system.getAvatarObjectKey(), system.getEmail(), system.getPhoneNumber()));
        }

        // General rate limit FIRST: allowSmsTrial() unconditionally
        // increments its counter just by being called, so if it ran first
        // and this request then got rejected by the 45s cooldown below
        // anyway, it would burn one of only 2 real SMS trials on a request
        // that never even reached Bird. Checking the free-to-retry general
        // limit first means only a request that's actually about to be
        // attempted ever counts against the expensive SMS-specific cap.
        if (!rateLimiter.allowIp(clientIp(httpRequest)) || !rateLimiter.allowIdentifier(identifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new OtpRequestError("RATE_LIMITED", null));
        }

        // SMS costs real money per send — a much tighter, dedicated cap
        // (2 total) on top of the general rate limit above. Email has no
        // such cap, so this only ever runs for the phone channel.
        if (isPhone && !rateLimiter.allowSmsTrial(identifier)) {
            long retryAfterSeconds = rateLimiter.smsTrialResetIn(identifier).getSeconds();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new OtpRequestError("SMS_TRIAL_LIMIT_REACHED", retryAfterSeconds));
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
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerifyRequest request) {
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

        // A brand-new account can never be suspended (nothing for an admin to
        // have acted on yet) — only a returning account needs the check.
        // Rejected before the welcome-message/device-cap checks below: an
        // admin-suspended account shouldn't be able to link a new device at
        // all, not even to find out it's suspended only after one more
        // session row gets created.
        if (!isNewUser && user.isSuspended()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new SuspendedError("ACCOUNT_SUSPENDED", user.getSuspendedReason()));
        }

        if (isNewUser) {
            systemAccountService.sendWelcomeMessage(user);
        }

        // Any number of devices, any mix of platforms, may be linked to one
        // account at once (phones, tablets, and web all share one pool) —
        // capped at maxLinkedDevices. A brand-new user always passes this
        // (zero existing sessions); a returning account is blocked once it's
        // already at the cap, and must remove a device (master-only, see
        // SessionController#revoke) before another can link.
        List<Session> activeSessions = sessionRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(user.getId());
        if (activeSessions.size() >= maxLinkedDevices) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new DeviceLimitError("DEVICE_LIMIT_REACHED", maxLinkedDevices));
        }

        // True iff this is the account's very first session (a brand-new
        // user, or every prior session was cascade-revoked by a previous
        // master signing out and nobody has logged back in since) — see
        // Session#isMaster's own doc comment for the full rule.
        boolean isMaster = activeSessions.isEmpty();

        String jti = UUID.randomUUID().toString();
        String token = jwtIssuer.issue(user.getId().toString(), Duration.ofDays(30), jti);
        sessionRepository.save(new Session(jti, user.getId(), request.deviceLabel(), Instant.now(), request.platform(), isMaster));
        return ResponseEntity.ok(new TokenResponse(token, user.getId().toString(), user.getDisplayName(),
                user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber()));
    }
}
