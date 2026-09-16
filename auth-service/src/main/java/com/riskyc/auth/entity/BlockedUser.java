package com.riskyc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * A block record only — this does NOT yet enforce anything cross-service
 * (messaging-service doesn't check it before delivering a message/call).
 * Scoped this way deliberately: the visible "Block" action + a queryable
 * block list is what the contact-details screen needs right now; wiring
 * actual message/call suppression is a separate, larger cross-service
 * change (messaging-service would need to check this table, or a mirror of
 * it, before every send/call-invite) left for a follow-up.
 */
@Entity
@Table(name = "blocked_user", uniqueConstraints = @UniqueConstraint(columnNames = { "blocker_id", "blocked_id" }))
public class BlockedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "blocker_id", nullable = false)
    private UUID blockerId;

    @Column(name = "blocked_id", nullable = false)
    private UUID blockedId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BlockedUser() {
        // JPA
    }

    public BlockedUser(UUID blockerId, UUID blockedId, Instant createdAt) {
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
