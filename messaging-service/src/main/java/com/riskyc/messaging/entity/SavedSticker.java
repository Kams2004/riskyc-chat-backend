package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One sticker in one user's own collection — one row per (user, objectKey).
 * A sticker is just an ordinary MinIO object (created either by "Create
 * sticker" turning a picked image into one, or by saving one someone else
 * sent), so this table only ever stores the pointer, same as every other
 * media reference in this codebase; nothing here is deleted when the
 * originating message is deleted, since a saved sticker is independent of
 * whatever message first introduced it.
 */
@Entity
@Table(name = "saved_sticker", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "object_key" }))
public class SavedSticker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    protected SavedSticker() {
        // JPA
    }

    public SavedSticker(String userId, String objectKey, Instant savedAt) {
        this.userId = userId;
        this.objectKey = objectKey;
        this.savedAt = savedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
