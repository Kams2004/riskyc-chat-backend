package com.riskyc.presence.controller;

import com.riskyc.presence.service.PresenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

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
