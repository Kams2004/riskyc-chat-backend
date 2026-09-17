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
 * One emoji reaction per (message, user) — sending a new emoji replaces the
 * old one, sending the SAME emoji again removes it (a toggle), matching
 * WhatsApp. Persisted and broadcast (unlike starred, which is purely
 * client-local) since everyone in the conversation needs to see everyone
 * else's reactions — see ChatController#react.
 */
@Entity
@Table(name = "message_reaction", uniqueConstraints = @UniqueConstraint(columnNames = { "message_id", "user_id" }))
public class MessageReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "emoji", nullable = false)
    private String emoji;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MessageReaction() {
        // JPA
    }

    public MessageReaction(String messageId, String userId, String emoji, Instant createdAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.emoji = emoji;
        this.createdAt = createdAt;
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

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
