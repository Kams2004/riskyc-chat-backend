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
 * "Delete for me" marker — one row per (message, user) who chose to hide a
 * message on their own side only. Deliberately server-tracked rather than a
 * purely client-local hide: the client re-fetches history on every
 * conversation open (see MessageHistoryController) and a fresh install has
 * no local state at all, so a client-only hide would silently un-delete
 * itself. Same shape/reasoning as MessageReceipt (a per-user server row
 * rather than a client-only flag). Never broadcast to other participants —
 * this table's only consumer is MessageHistoryController filtering the
 * caller's own history.
 */
@Entity
@Table(name = "message_deletion", uniqueConstraints = @UniqueConstraint(columnNames = { "message_id", "user_id" }))
public class MessageDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    protected MessageDeletion() {
        // JPA
    }

    public MessageDeletion(String messageId, String userId, Instant deletedAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.deletedAt = deletedAt;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
