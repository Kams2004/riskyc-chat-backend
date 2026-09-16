package com.riskyc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Write-only from the client's perspective — there's no "my reports" read endpoint; this exists purely for the account being reported for later moderator review, none built yet. */
@Entity
@Table(name = "user_report")
public class UserReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "reported_id", nullable = false)
    private UUID reportedId;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserReport() {
        // JPA
    }

    public UserReport(UUID reporterId, UUID reportedId, String reason, Instant createdAt) {
        this.reporterId = reporterId;
        this.reportedId = reportedId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }
}
