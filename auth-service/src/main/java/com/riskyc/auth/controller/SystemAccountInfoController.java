package com.riskyc.auth.controller;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.SystemAccountService;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Public-facing (any signed-in user) and internal (service-to-service)
 * lookups for the RiskyC Fashion official account — separate from
 * UserController/SystemAccountService's own signup/welcome-message
 * concerns. The public endpoint is what the mobile client uses to (a)
 * disable the composer when chatting TO this account (read-only,
 * broadcast-only, same as WhatsApp's own official accounts) and (b) render
 * its "details" page (description + website). The internal ones back
 * messaging-service's broadcast endpoint: it needs to confirm a broadcast
 * caller really is this account, and needs the full user-id list to fan a
 * broadcast out to.
 */
@RestController
public class SystemAccountInfoController {

    private final SystemAccountService systemAccountService;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final String internalApiKey;

    public SystemAccountInfoController(SystemAccountService systemAccountService, UserRepository userRepository,
                                        JwtIssuer jwtIssuer, @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.systemAccountService = systemAccountService;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
        this.internalApiKey = internalApiKey;
    }

    public record SystemAccountResult(String userId, String displayName, String avatarObjectKey, String description,
                                       String websiteUrl) {
    }

    public record SystemAccountIdResult(String userId) {
    }

    @GetMapping("/api/system-account")
    public SystemAccountResult get(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireCaller(authorization);
        if (!systemAccountService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No official account configured");
        }
        User system = systemAccountService.findOrCreateAccount();
        return new SystemAccountResult(system.getId().toString(), system.getDisplayName(), system.getAvatarObjectKey(),
                systemAccountService.getDescription(), systemAccountService.getWebsiteUrl());
    }

    /** Shared-secret protected — see SessionController's own doc comment on this exact pattern. */
    @GetMapping("/internal/system-account/id")
    public SystemAccountIdResult internalId(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        requireInternalKey(apiKey);
        if (!systemAccountService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No official account configured");
        }
        return new SystemAccountIdResult(systemAccountService.findOrCreateAccount().getId().toString());
    }

    /**
     * Every user id in the system — messaging-service has no user list of
     * its own (it only ever learns ids through messages/group membership
     * it's already seen), so a broadcast fan-out has to ask here instead.
     * A flat, unpaginated list is an explicit MVP tradeoff (same category
     * as this codebase's other "fine for a small user base, revisit if it
     * ever grows" choices) — broadcasting is a rare, deliberate admin
     * action, not a hot path.
     */
    @GetMapping("/internal/users/all-ids")
    public List<String> internalAllIds(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        requireInternalKey(apiKey);
        return userRepository.findAll().stream().map(u -> u.getId().toString()).toList();
    }

    private void requireInternalKey(String apiKey) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }

    private void requireCaller(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
