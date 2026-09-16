package com.riskyc.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Only auth-service needs CORS — it's the only service a browser (the new
 * web sign-in form) talks to directly; the mobile app isn't subject to
 * same-origin policy at all, so nothing else here changes for it.
 * Allow-listed explicitly (never "*") because credentials/bearer tokens
 * flow through this API — a wildcard origin would let any website's script
 * ride a visitor's browser to hit it.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${riskyc.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/auth/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
        // The web chat client also needs GET (profile lookups) and DELETE
        // (account deletion) here, plus PUT for profile/identifier updates.
        registry.addMapping("/api/users/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "PUT", "DELETE", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
        // /internal/** is deliberately NOT mapped here — it's server-to-server
        // traffic (messaging-service's RevokedJtiCache poller), protected by
        // a shared secret instead (see SessionController), and should never
        // be reachable from a browser at all.
        registry.addMapping("/api/sessions/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }
}
