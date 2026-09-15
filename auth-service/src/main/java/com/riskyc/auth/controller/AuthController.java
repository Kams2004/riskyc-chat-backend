package com.riskyc.auth.controller;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.EmailOtpSender;
import com.riskyc.auth.service.OtpRateLimiter;
import com.riskyc.auth.service.OtpService;
import com.riskyc.common.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * OTP verification accepts EITHER a phone number or an email address as the
 * identifier — exactly one must be present on every request. Phone delivery
 * is still a stub (logged to stdout, see OtpService's doc comment on
 * OtpRequest handling below); email delivery is real, via EmailOtpSender.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Deliberately permissive (this isn't validating deliverability, just
    // rejecting obvious garbage before it reaches an SMTP call or gets
    // stored) — a real provider will reject anything it can't deliver anyway.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{7,15}$");

    private final OtpService otpService;
    private final OtpRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final EmailOtpSender emailOtpSender;

    public AuthController(OtpService otpService, OtpRateLimiter rateLimiter, UserRepository userRepository,
                           JwtIssuer jwtIssuer, EmailOtpSender emailOtpSender) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
        this.emailOtpSender = emailOtpSender;
    }

    public record OtpRequest(String phoneNumber, String email) {
    }

    public record OtpVerifyRequest(String phoneNumber, String email, String code, String displayName) {
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
    public ResponseEntity<Void> requestOtp(@RequestBody OtpRequest request, HttpServletRequest httpRequest) {
        String identifier;
        try {
            identifier = identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (!rateLimiter.allowIp(clientIp(httpRequest)) || !rateLimiter.allowIdentifier(identifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String code = otpService.generateAndStore(identifier);
        if (request.email() != null && !request.email().isBlank()) {
            emailOtpSender.sendOtp(request.email(), code);
        } else {
            // TODO: wire up an SMS provider. Logging for local/dev use only.
            System.out.printf("OTP for %s: %s%n", request.phoneNumber(), code);
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
