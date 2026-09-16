package com.riskyc.presence.controller;

import com.riskyc.presence.service.PresenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Documented MVP gap: no authentication check of any kind today — not even
 * bare JWT signature verification, let alone the session-revocation checks
 * added to auth-service/messaging-service. Anyone who can reach this service
 * can post a heartbeat/offline event or read presence for any userId. This
 * service is Postgres-free (Redis-only), so wiring in JwtIssuer + the
 * revocation checks the other two services now have would be a reasonable
 * next step, not attempted in this pass — blast radius here is low (online/
 * last-seen signal only, no message content or media), which is why this
 * was deprioritized rather than fixed alongside media-service's gap.
 */
@RestController
@RequestMapping("/api/presence")
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    public record PresenceResponse(String userId, boolean online, Instant lastSeen) {
    }

    @PostMapping("/{userId}/heartbeat")
    public void heartbeat(@PathVariable String userId) {
        presenceService.heartbeat(userId);
    }

    @PostMapping("/{userId}/offline")
    public void markOffline(@PathVariable String userId) {
        presenceService.markOffline(userId);
    }

    @GetMapping("/{userId}")
    public PresenceResponse get(@PathVariable String userId) {
        boolean online = presenceService.isOnline(userId);
        Instant lastSeen = presenceService.lastSeen(userId).orElse(null);
        return new PresenceResponse(userId, online, lastSeen);
    }
}
