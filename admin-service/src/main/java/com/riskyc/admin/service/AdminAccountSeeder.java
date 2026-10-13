package com.riskyc.admin.service;

import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.repository.AdminAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates the one bootstrap admin account the very first time this service
 * ever starts against an empty admin_account table — idempotent across every
 * later restart (count() > 0 short-circuits immediately). If
 * RISKYC_ADMIN_DEFAULT_PASSWORD is set, that password is used (hashed, never
 * logged — the operator already knows it). Otherwise a fresh cryptographically
 * random password is generated and logged ONCE, at WARNING level so it
 * survives a default INFO-suppressing log config, then never stored or
 * logged in plaintext again — only its BCrypt hash persists.
 */
@Component
public class AdminAccountSeeder implements ApplicationRunner {

    private static final Logger log = Logger.getLogger(AdminAccountSeeder.class.getName());

    // 20 characters from a 64-character mixed-case+digit+symbol charset
    // (visually-ambiguous I/l/1/0/O excluded, since a human has to read
    // this off a log line and type it once) — roughly 120 bits of entropy,
    // far beyond any realistic brute-force concern for a rate-limited login.
    private static final String PASSWORD_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*";
    private static final int PASSWORD_LENGTH = 20;

    private final AdminAccountRepository adminAccountRepository;
    private final BCryptPasswordEncoder encoder;
    private final String defaultUsername;
    private final String defaultPassword;

    public AdminAccountSeeder(AdminAccountRepository adminAccountRepository, BCryptPasswordEncoder encoder,
                               @Value("${riskyc.admin.default-username}") String defaultUsername,
                               @Value("${riskyc.admin.default-password:}") String defaultPassword) {
        this.adminAccountRepository = adminAccountRepository;
        this.encoder = encoder;
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminAccountRepository.count() > 0) {
            return;
        }
        boolean generated = defaultPassword.isBlank();
        String password = generated ? generateStrongPassword() : defaultPassword;
        adminAccountRepository.save(new AdminAccount(defaultUsername, encoder.encode(password), "Default Admin"));
        if (generated) {
            log.log(Level.WARNING, "========== INITIAL ADMIN PASSWORD (shown once, username: {0}): {1} ==========",
                    new Object[] { defaultUsername, password });
        }
    }

    private String generateStrongPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARSET.charAt(random.nextInt(PASSWORD_CHARSET.length())));
        }
        return sb.toString();
    }
}
