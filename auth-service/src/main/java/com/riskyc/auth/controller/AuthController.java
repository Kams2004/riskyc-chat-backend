package com.riskyc.auth.controller;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.EmailOtpSender;
import com.riskyc.auth.service.OtpRateLimiter;
import com.riskyc.auth.service.OtpService;
import com.riskyc.auth.service.SmsOtpSender;
import com.riskyc.common.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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

    // Deliberately permissive (this isn't validating deliverability, just
    // rejecting obvious garbage before it reaches an SMTP call or gets
    // stored) — a real provider will reject anything it can't deliver anyway.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    // Requires a leading '+' (E.164) — Bird's SMS API rejects anything else
    // outright, so a number that would just bounce off the SMS provider is
    // caught here instead, with a clear 400, rather than surfacing as an
    // opaque failure from SmsOtpSender.
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[0-9]{7,15}$");

    private final OtpService otpService;
    private final OtpRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final EmailOtpSender emailOtpSender;
    private final SmsOtpSender smsOtpSender;

    public AuthController(OtpService otpService, OtpRateLimiter rateLimiter, UserRepository userRepository,
                           JwtIssuer jwtIssuer, EmailOtpSender emailOtpSender, SmsOtpSender smsOtpSender) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
        this.emailOtpSender = emailOtpSender;
        this.smsOtpSender = smsOtpSender;
    }

    public record OtpRequest(String phoneNumber, String email) {
    }

    public record OtpVerifyRequest(String phoneNumber, String email, String code, String displayName) {
    }

    /** Returned as the body of a 429 so the client can tell a hard SMS trial cutoff apart from an ordinary "slow down". */
    public record OtpRequestError(String reason) {
    }

    public record TokenResponse(String accessToken, String userId, String displayName, String avatarObjectKey, String email, String phoneNumber) {
    }

    private static String identifierOf(String phoneNumber, String email) {
        boolean hasPhone = phoneNumber != null && !phoneNumber.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (hasPhone == hasEmail) {
            throw new IllegalArgumentException("Provide exactly one of phoneNumber or email");
        }
        if (hasEmail && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Malformed email");
        }
        if (hasPhone && !PHONE_PATTERN.matcher(phoneNumber).matches()) {
            throw new IllegalArgumentException("Malformed phone number");
        }
        return hasPhone ? phoneNumber : email;
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
    public ResponseEntity<OtpRequestError> requestOtp(@RequestBody OtpRequest request, HttpServletRequest httpRequest) {
        String identifier;
        boolean isPhone = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        try {
            identifier = identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
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
            identifier = identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (!otpService.verify(identifier, request.code())) {
            return ResponseEntity.status(401).build();
        }

        boolean byEmail = request.email() != null && !request.email().isBlank();
        User user = byEmail
                ? userRepository.findByEmail(identifier)
                        .orElseGet(() -> userRepository.save(User.withEmail(identifier, request.displayName())))
                : userRepository.findByPhoneNumber(identifier)
                        .orElseGet(() -> userRepository.save(User.withPhoneNumber(identifier, request.displayName())));

        String token = jwtIssuer.issue(user.getId().toString(), Duration.ofDays(30));
        return ResponseEntity.ok(new TokenResponse(token, user.getId().toString(), user.getDisplayName(),
                user.getAvatarObjectKey(), user.getEmail(), user.getPhoneNumber()));
    }
}
