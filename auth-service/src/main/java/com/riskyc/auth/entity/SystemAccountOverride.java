package com.riskyc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-edited overrides for the system account's description/website-url —
 * these two fields otherwise only ever come from read-only @Value config
 * (riskyc.system-account.description/website-url, see SystemAccountService),
 * which has nowhere to persist an edit made through admin-service. One row
 * at most, keyed by the system account's own User.id (no surrogate key
 * needed — this is a 1:1 extension of that single row, not a general-purpose
 * table). displayName/avatarObjectKey need no equivalent: they already live
 * directly on the User row and already have setters.
 */
@Entity
@Table(name = "system_account_override")
public class SystemAccountOverride {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemAccountOverride() {
        // JPA
    }

    public SystemAccountOverride(UUID userId, String description, String websiteUrl, Instant updatedAt) {
        this.userId = userId;
        this.description = description;
        this.websiteUrl = websiteUrl;
        this.updatedAt = updatedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDescription() {
        return description;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
