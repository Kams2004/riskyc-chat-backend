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
import com.riskyc.common.security.JwtIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets a signed-in user find other people to start a chat with — everyone
 * with an account is discoverable by name/email/phone, no separate contacts
 * step (there's no contacts/groups-service yet, see architecture notes).
 * The caller's identity comes from their own bearer token, not a client-
 * supplied id, so one user can't be excluded from another's search results,
 * and can only ever update their own profile.
 */
@RestController
public class UserController {

    private static final Logger log = Logger.getLogger(UserController.class.getName());

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final JwtIssuer jwtIssuer;
    private final OtpService otpService;
    private final OtpRateLimiter rateLimiter;
    private final EmailOtpSender emailOtpSender;
    private final SmsOtpSender smsOtpSender;

    public UserController(UserRepository userRepository, SessionRepository sessionRepository, JwtIssuer jwtIssuer,
                           OtpService otpService, OtpRateLimiter rateLimiter, EmailOtpSender emailOtpSender,
                           SmsOtpSender smsOtpSender) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.jwtIssuer = jwtIssuer;
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.emailOtpSender = emailOtpSender;
        this.smsOtpSender = smsOtpSender;
    }

    public record UserResult(String userId, String displayName, String email, String phoneNumber, String avatarObjectKey) {
    }

    public record UpdateProfileRequest(String displayName, String avatarObjectKey) {
    }

    public record IdentifierChangeRequest(String newPhoneNumber, String newEmail) {
    }

    public record IdentifierChangeConfirm(String newPhoneNumber, String newEmail, String code) {
    }

    /** Same shape/reasons as AuthController.OtpRequestError — RATE_LIMITED, SMS_TRIAL_LIMIT_REACHED, or IDENTIFIER_ALREADY_IN_USE. */
    public record IdentifierChangeError(String reason) {
    }

    public record MatchContactsRequest(List<String> phoneNumbers, List<String> emails) {
    }

    /**
     * Feeds mobile's contacts-only discovery: the client reads its device
     * contacts once, POSTs the raw numbers/emails here, and gets back only
     * the accounts that match — the caller never learns which OTHER numbers
     * in their contact list do or don't have an account, since non-matches
     * are simply absent from the response.
     */
    @PostMapping("/api/users/match-contacts")
    public List<UserResult> matchContacts(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody MatchContactsRequest request) {
        UUID callerId = callerIdFrom(authorization);
        Map<UUID, User> matched = new LinkedHashMap<>();
        if (request.phoneNumbers() != null && !request.phoneNumbers().isEmpty()) {
            for (User u : userRepository.findByPhoneNumberIn(request.phoneNumbers())) {
                matched.put(u.getId(), u);
            }
        }
        if (request.emails() != null && !request.emails().isEmpty()) {
            for (User u : userRepository.findByEmailIn(request.emails())) {
                matched.put(u.getId(), u);
            }
        }
        matched.remove(callerId);
        return matched.values().stream().map(this::toResult).toList();
    }

    @GetMapping("/api/users")
    public List<UserResult> search(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(name = "q", defaultValue = "") String query) {
        UUID callerId = callerIdFrom(authorization);
        return userRepository.search(callerId, query.trim()).stream()
                .map(this::toResult)
                .toList();
    }

    @GetMapping("/api/users/{userId}")
    public UserResult get(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID userId) {
        callerIdFrom(authorization);
        return userRepository.findById(userId)
                .map(this::toResult)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
    }

    @PutMapping("/api/users/me")
    public UserResult updateMe(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody UpdateProfileRequest request) {
        UUID callerId = callerIdFrom(authorization);
        User user = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.avatarObjectKey() != null) {
            user.setAvatarObjectKey(request.avatarObjectKey());
        }
        userRepository.save(user);
        return toResult(user);
    }

    /**
     * Step 1 of changing a signed-in user's email/phone: validates the new
     * identifier, rejects it up front (409) if another account already has
     * it — avoiding a wasted OTP send — then applies the same rate limits
     * signup uses, including the 2-trial SMS cap (OtpRateLimiter.allowSmsTrial
     * is keyed only by phone number, not by "signup vs change", so a number
     * that already used its trials during signup is correctly capped here
     * too). Sends the code to the NEW identifier; nothing on the account
     * changes until confirm() verifies it.
     */
    @PostMapping("/api/users/me/identifier/request-otp")
    public ResponseEntity<IdentifierChangeError> requestIdentifierChange(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody IdentifierChangeRequest request, HttpServletRequest httpRequest) {
        callerIdFrom(authorization);
        String newIdentifier;
        boolean isPhone = request.newPhoneNumber() != null && !request.newPhoneNumber().isBlank();
        try {
            newIdentifier = IdentifierValidator.identifierOf(request.newPhoneNumber(), request.newEmail());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        boolean alreadyTaken = isPhone
                ? userRepository.existsByPhoneNumber(newIdentifier)
                : userRepository.existsByEmail(newIdentifier);
        if (alreadyTaken) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new IdentifierChangeError("IDENTIFIER_ALREADY_IN_USE"));
        }

        if (!rateLimiter.allowIp(clientIp(httpRequest)) || !rateLimiter.allowIdentifier(newIdentifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new IdentifierChangeError("RATE_LIMITED"));
        }
        if (isPhone && !rateLimiter.allowSmsTrial(newIdentifier)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new IdentifierChangeError("SMS_TRIAL_LIMIT_REACHED"));
        }

        String code = otpService.generateAndStore(newIdentifier);
        try {
            if (isPhone) {
                smsOtpSender.sendOtp(newIdentifier, code);
            } else {
                emailOtpSender.sendOtp(newIdentifier, code);
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Identifier-change OTP send failed for " + (isPhone ? "phone" : "email") + " channel", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * Step 2: verifies the code sent above, then applies the new identifier
     * to the caller's own account. A save() here that races another account
     * claiming the same identifier in between is still caught by
     * GlobalExceptionHandler (DataIntegrityViolationException -> 409) — the
     * pre-check above handles the common case, this is the backstop.
     */
    @PostMapping("/api/users/me/identifier/confirm")
    public ResponseEntity<UserResult> confirmIdentifierChange(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody IdentifierChangeConfirm request) {
        UUID callerId = callerIdFrom(authorization);
        String newIdentifier;
        boolean isPhone = request.newPhoneNumber() != null && !request.newPhoneNumber().isBlank();
        try {
            newIdentifier = IdentifierValidator.identifierOf(request.newPhoneNumber(), request.newEmail());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (!otpService.verify(newIdentifier, request.code())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        if (isPhone) {
            user.setPhoneNumber(newIdentifier);
        } else {
            user.setEmail(newIdentifier);
        }
        userRepository.save(user);
        return ResponseEntity.ok(toResult(user));
    }

    /**
     * Deletes the caller's own account row. Their messages/conversations
     * in messaging-service are left as-is (a different service's data, no
     * cross-service cascade) — this only removes the account itself, which
     * is what stops them being discoverable/loggable-into again.
     */
    @DeleteMapping("/api/users/me")
    public void deleteMe(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID callerId = callerIdFrom(authorization);
        userRepository.deleteById(callerId);
    }

    /** Same caveat as AuthController's: correct only as long as nothing sits in front of this service yet. */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private UUID callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        String token = authorization.substring("Bearer ".length());
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(token);
            // Direct DB check, not a cache — auth-service owns the Session
            // table itself, so there's no polling-lag tradeoff to make here
            // the way messaging-service's RevokedJtiCache has to.
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

    private UserResult toResult(User user) {
        return new UserResult(user.getId().toString(), user.getDisplayName(), user.getEmail(), user.getPhoneNumber(),
                user.getAvatarObjectKey());
    }
}
