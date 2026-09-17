package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per conversation (1:1 or group — same opaque conversationId every
 * other endpoint already uses) that has disappearing messages turned on.
 * No row at all means "off", same convention as MutedConversation's
 * presence-means-true. Shared, not per-user — like WhatsApp, any
 * participant setting it applies to everyone in the conversation, and it
 * only affects messages sent AFTER it's set (see ChatController#send,
 * which reads this at send time to stamp the new message's expiresAt).
 */
@Entity
@Table(name = "disappearing_message_settings")
public class DisappearingMessageSettings {

    @Id
    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    protected DisappearingMessageSettings() {
        // JPA
    }

    public DisappearingMessageSettings(String conversationId, int durationSeconds, String updatedBy) {
        this.conversationId = conversationId;
        this.durationSeconds = durationSeconds;
        this.updatedBy = updatedBy;
    }

    public String getConversationId() {
        return conversationId;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
