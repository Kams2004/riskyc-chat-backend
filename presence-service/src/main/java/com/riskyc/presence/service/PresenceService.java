package com.riskyc.presence.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tracks presence in Redis (not in-memory) so any presence-service replica,
 * and any messaging-service node, can answer "is this user online" the same
 * way. A key with a short TTL doubles as both the online flag and a
 * heartbeat: clients must re-touch it periodically or it expires and the
 * user reads as offline.
 */
@Service
public class PresenceService {

    private static final Duration ONLINE_TTL = Duration.ofSeconds(60);
    private static final String ONLINE_KEY_PREFIX = "presence:online:";
    private static final String LAST_SEEN_KEY_PREFIX = "presence:last-seen:";

    private final StringRedisTemplate redisTemplate;

    public PresenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void heartbeat(String userId) {
        redisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, Instant.now().toString(), ONLINE_TTL);
    }

    public void markOffline(String userId) {
        redisTemplate.delete(ONLINE_KEY_PREFIX + userId);
        redisTemplate.opsForValue().set(LAST_SEEN_KEY_PREFIX + userId, Instant.now().toString());
    }

    public boolean isOnline(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ONLINE_KEY_PREFIX + userId));
    }

    public Optional<Instant> lastSeen(String userId) {
        String value = redisTemplate.opsForValue().get(LAST_SEEN_KEY_PREFIX + userId);
        return Optional.ofNullable(value).map(Instant::parse);
    }
}
