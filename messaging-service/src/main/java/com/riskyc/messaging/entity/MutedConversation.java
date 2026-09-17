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
 * A muted conversation for one user — one row per (user, conversation).
 * conversationId is the same opaque string every message/history endpoint
 * already uses (a 1:1's sorted-pair id, or a groupId), so this table works
 * for both without needing to know which kind it is. Server-side (not a
 * client-local flag) specifically so ChatController#send can skip that
 * user's push notification before it ever goes out — a client-only mute can
 * silence in-app banners but can't stop a push the server already sent.
 */
@Entity
@Table(name = "muted_conversation", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "conversation_id" }))
public class MutedConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "muted_at", nullable = false)
    private Instant mutedAt;

    protected MutedConversation() {
        // JPA
    }

    public MutedConversation(String userId, String conversationId, Instant mutedAt) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.mutedAt = mutedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getMutedAt() {
        return mutedAt;
    }
}
