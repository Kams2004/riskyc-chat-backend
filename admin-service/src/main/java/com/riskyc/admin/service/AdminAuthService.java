package com.riskyc.admin.service;

import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.repository.AdminAccountRepository;
import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AdminAuthService {

    private final AdminAccountRepository adminAccountRepository;
    private final BCryptPasswordEncoder encoder;
    private final JwtIssuer adminJwtIssuer;
    private final AdminJwtRevocationStore revocationStore;
    private final Duration jwtTtl;

    public AdminAuthService(AdminAccountRepository adminAccountRepository, BCryptPasswordEncoder encoder,
                             JwtIssuer adminJwtIssuer, AdminJwtRevocationStore revocationStore,
                             @Value("${riskyc.admin.jwt-ttl-hours:12}") long jwtTtlHours) {
        this.adminAccountRepository = adminAccountRepository;
        this.encoder = encoder;
        this.adminJwtIssuer = adminJwtIssuer;
        this.revocationStore = revocationStore;
        this.jwtTtl = Duration.ofHours(jwtTtlHours);
    }

    public record LoginResult(String accessToken, String username, String displayName, Instant expiresAt) {
    }

    /** @return empty if the credentials don't match — callers decide how to report that (401), never thrown as an exception here since "wrong password" isn't exceptional. */
    public Optional<LoginResult> login(String username, String password) {
        Optional<AdminAccount> account = adminAccountRepository.findByUsername(username);
        if (account.isEmpty() || !encoder.matches(password, account.get().getPasswordHash())) {
            return Optional.empty();
        }
        AdminAccount acc = account.get();
        String jti = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(jwtTtl);
        String token = adminJwtIssuer.issue(acc.getId().toString(), jwtTtl, jti);
        return Optional.of(new LoginResult(token, acc.getUsername(), acc.getDisplayName(), expiresAt));
    }

    public void logout(String authorization) {
        JwtIssuer.JwtClaims claims = verify(authorization);
        // The real expiry isn't carried in JwtClaims, but the revocation
        // store only needs AN upper bound to know when it's safe to forget
        // this jti again — the configured TTL from "now" is a safe
        // overestimate (the token can only be younger than that).
        revocationStore.revoke(claims.jti(), Instant.now().plus(jwtTtl));
    }

    /** Verifies the bearer token (signature + not-revoked) and loads the caller's own account — used by every /api/admin/** controller besides auth itself. */
    public AdminAccount callerFrom(String authorization) {
        JwtIssuer.JwtClaims claims = verify(authorization);
        return adminAccountRepository.findById(UUID.fromString(claims.subject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin account no longer exists"));
    }

    private JwtIssuer.JwtClaims verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = adminJwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revocationStore.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
