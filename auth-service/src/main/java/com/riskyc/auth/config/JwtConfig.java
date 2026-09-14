package com.riskyc.auth.config;

import com.riskyc.common.security.JwtIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtIssuer jwtIssuer(@Value("${riskyc.jwt.secret}") String base64Secret) {
        return new JwtIssuer(base64Secret);
    }
}
