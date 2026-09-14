package com.riskyc.auth.controller;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.EmailOtpSender;
import com.riskyc.auth.service.OtpService;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * OTP verification accepts EITHER a phone number or an email address as the
 * identifier — exactly one must be present on every request. Phone delivery
 * is still a stub (logged to stdout, see OtpService's doc comment on
 * OtpRequest handling below); email delivery is real, via EmailOtpSender.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final OtpService otpService;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final EmailOtpSender emailOtpSender;

    public AuthController(OtpService otpService, UserRepository userRepository, JwtIssuer jwtIssuer,
                           EmailOtpSender emailOtpSender) {
        this.otpService = otpService;
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
        return hasPhone ? phoneNumber : email;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@RequestBody OtpRequest request) {
        String identifier;
        try {
            identifier = identifierOf(request.phoneNumber(), request.email());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
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
