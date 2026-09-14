package com.riskyc.auth.controller;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

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

    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;

    public UserController(UserRepository userRepository, JwtIssuer jwtIssuer) {
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    public record UserResult(String userId, String displayName, String email, String phoneNumber, String avatarObjectKey) {
    }

    public record UpdateProfileRequest(String displayName, String avatarObjectKey) {
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

    private UUID callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        String token = authorization.substring("Bearer ".length());
        try {
            return UUID.fromString(jwtIssuer.verifyAndGetSubject(token));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    private UserResult toResult(User user) {
        return new UserResult(user.getId().toString(), user.getDisplayName(), user.getEmail(), user.getPhoneNumber(),
                user.getAvatarObjectKey());
    }
}
