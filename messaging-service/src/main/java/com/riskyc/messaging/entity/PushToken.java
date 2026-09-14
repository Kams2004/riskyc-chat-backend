package com.riskyc.messaging.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per installed device (Expo push token) — a user can have several
 * (phone + tablet, or a reinstall leaving the old token stale but harmless).
 * The token itself is the primary key since Expo issues a stable one per
 * installation; re-registering the same token just refreshes updatedAt.
 */
@Entity
@Table(name = "push_token")
public class PushToken {

    @Id
    private String token;

    private String userId;

    /** "ios" or "android" — informational only for now, nothing branches on it yet. */
    private String platform;

    private Instant updatedAt;

    protected PushToken() {
    }

    public PushToken(String token, String userId, String platform, Instant updatedAt) {
        this.token = token;
        this.userId = userId;
        this.platform = platform;
        this.updatedAt = updatedAt;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
