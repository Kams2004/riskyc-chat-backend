package com.riskyc.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of admin-service's "blocked contacts" view — a BlockedUser joined with both parties' display names. */
public record AdminBlockedRow(UUID id, UUID blockerId, String blockerDisplayName,
                               UUID blockedId, String blockedDisplayName, Instant createdAt) {
}
