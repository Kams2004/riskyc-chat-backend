package com.riskyc.admin.controller;

import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.AdminLoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final AdminLoginRateLimiter rateLimiter;

    public AdminAuthController(AdminAuthService adminAuthService, AdminLoginRateLimiter rateLimiter) {
        this.adminAuthService = adminAuthService;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginError(String reason, Long retryAfterSeconds) {
    }

    public record MeResult(String username, String displayName) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        if (!rateLimiter.allowIp(ip)) {
            long retryAfterSeconds = rateLimiter.resetIn(ip).getSeconds();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new LoginError("RATE_LIMITED", retryAfterSeconds));
        }
        return adminAuthService.login(request.username(), request.password())
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(
                        new LoginResponse(result.accessToken(), result.username(), result.displayName(), result.expiresAt().toString())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new LoginError("INVALID_CREDENTIALS", null)));
    }

    public record LoginResponse(String accessToken, String username, String displayName, String expiresAt) {
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        adminAuthService.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResult me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AdminAccount account = adminAuthService.callerFrom(authorization);
        return new MeResult(account.getUsername(), account.getDisplayName());
    }

    /** Same caveat as every other service's own clientIp helper: correct only as long as nothing (proxy-wise) sits in front of this service yet. */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
