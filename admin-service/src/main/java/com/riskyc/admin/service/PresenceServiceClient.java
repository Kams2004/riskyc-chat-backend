package com.riskyc.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * admin-service's only way to reach presence-service's data — just the one
 * online-count tile. No X-Internal-Api-Key here: presence-service has no
 * authentication of any kind on any of its endpoints today (a pre-existing,
 * documented gap — see PresenceController's own doc comment) and this call
 * doesn't change that posture, it just reads from it like anything else
 * reaching that service can.
 */
@Service
public class PresenceServiceClient {

    private static final Logger log = Logger.getLogger(PresenceServiceClient.class.getName());

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public PresenceServiceClient(@Value("${riskyc.presence-service.internal-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Best-effort — a presence-service hiccup shouldn't break the whole dashboard summary, just show this one tile as unknown. */
    public long onlineCount() {
        try {
            URI uri = URI.create(baseUrl + "/api/presence/online-count");
            Map<?, ?> body = restTemplate.getForObject(uri, Map.class);
            Object count = body != null ? body.get("onlineCount") : null;
            return count instanceof Number n ? n.longValue() : 0;
        } catch (RestClientException e) {
            log.log(Level.WARNING, "Failed to fetch online-count from presence-service", e);
            return 0;
        }
    }
}
