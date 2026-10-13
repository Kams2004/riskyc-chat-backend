package com.riskyc.admin.config;

import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class AdminBeansConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * A SEPARATE JwtIssuer instance from a distinct secret
     * (riskyc.admin.jwt-secret, never riskyc.jwt.secret) — this is the whole
     * reason a regular user's JWT can never verify against admin-service and
     * vice versa: different signing key, same reused common.JwtIssuer class,
     * zero changes needed to that shared module.
     */
    @Bean
    public JwtIssuer adminJwtIssuer(@Value("${riskyc.admin.jwt-secret}") String adminJwtSecret) {
        return new JwtIssuer(adminJwtSecret);
    }
}
