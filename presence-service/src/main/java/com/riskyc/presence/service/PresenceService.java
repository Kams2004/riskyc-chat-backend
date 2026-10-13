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
    // A sorted set alongside the per-user TTL'd keys above, score = expiry
    // epoch-millis — Redis has no "list/count all keys matching a TTL'd
    // prefix" primitive, so this is what lets admin-service's "active users"
    // count exist at all without an expensive KEYS/SCAN. Self-cleaning: a
    // read purges anyone whose score has already passed, so a crashed
    // client that never called markOffline still ages out within
    // ONLINE_TTL instead of inflating the count forever.
    private static final String ONLINE_ZSET_KEY = "presence:online-zset";

    private final StringRedisTemplate redisTemplate;

    public PresenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void heartbeat(String userId) {
        redisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, Instant.now().toString(), ONLINE_TTL);
        redisTemplate.opsForZSet().add(ONLINE_ZSET_KEY, userId, Instant.now().plus(ONLINE_TTL).toEpochMilli());
    }

    public void markOffline(String userId) {
        redisTemplate.delete(ONLINE_KEY_PREFIX + userId);
        redisTemplate.opsForValue().set(LAST_SEEN_KEY_PREFIX + userId, Instant.now().toString());
        redisTemplate.opsForZSet().remove(ONLINE_ZSET_KEY, userId);
    }

    public boolean isOnline(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ONLINE_KEY_PREFIX + userId));
    }

    public Optional<Instant> lastSeen(String userId) {
        String value = redisTemplate.opsForValue().get(LAST_SEEN_KEY_PREFIX + userId);
        return Optional.ofNullable(value).map(Instant::parse);
    }

    /** admin-service's "active/connected users" count. */
    public long onlineCount() {
        long now = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().removeRangeByScore(ONLINE_ZSET_KEY, Double.NEGATIVE_INFINITY, now);
        Long count = redisTemplate.opsForZSet().zCard(ONLINE_ZSET_KEY);
        return count != null ? count : 0;
    }
}
