package com.riskyc.messaging.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory mirror of auth-service's revoked-session jtis — checked by every
 * messaging-service controller/interceptor that verifies a bearer token,
 * after signature verification, so a "signed out" device stops being
 * trusted here within roughly one poll interval instead of only at the
 * JWT's natural 30-day expiry. Deliberately a poll-and-cache, not a
 * per-request call to auth-service: that would put auth-service's
 * availability/latency on the critical path of every single messaging
 * request, for a revocation check that doesn't need sub-second freshness.
 */
@Component
public class RevokedJtiCache {

    private static final Logger log = Logger.getLogger(RevokedJtiCache.class.getName());

    private final RestTemplate restTemplate = new RestTemplate();
    private final Set<String> revokedJtis = ConcurrentHashMap.newKeySet();
    private volatile Instant lastPolledAt = Instant.now().minusSeconds(3600);

    private final String authServiceInternalUrl;
    private final String internalApiKey;

    public RevokedJtiCache(@Value("${riskyc.auth-service.internal-url}") String authServiceInternalUrl,
                            @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.authServiceInternalUrl = authServiceInternalUrl;
        this.internalApiKey = internalApiKey;
    }

    public boolean isRevoked(String jti) {
        return jti != null && revokedJtis.contains(jti);
    }

    @Scheduled(fixedRate = 30_000)
    public void poll() {
        Instant since = lastPolledAt;
        Instant now = Instant.now();
        try {
            var headers = new org.springframework.http.HttpHeaders();
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            var request = new org.springframework.http.HttpEntity<Void>(headers);
            URI uri = URI.create(authServiceInternalUrl + "/internal/sessions/revoked-jtis?since=" + since);
            String[] body = restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET, request, String[].class).getBody();
            if (body != null) {
                revokedJtis.addAll(List.of(body));
            }
            lastPolledAt = now;
        } catch (RestClientException e) {
            // auth-service being briefly unreachable shouldn't take down
            // messaging-service's own request handling — the cache just
            // stays at its last-known-good state and retries next tick.
            log.log(Level.WARNING, "Failed to poll revoked-jti list from auth-service", e);
        }
    }
}
