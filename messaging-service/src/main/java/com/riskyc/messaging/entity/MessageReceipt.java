package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Per-member delivery/read tracking for a GROUP message — a 1:1 message
 * keeps using the single Message.status column unchanged; this table only
 * ever gets rows for messages that have a groupId set. The mobile client
 * aggregates these back into that same Message.status-shaped tick display
 * (see recomputeGroupMessageStatus), so no UI code needs to know this table
 * exists.
 */
@Entity
@Table(name = "message_receipt", uniqueConstraints = @UniqueConstraint(columnNames = { "message_id", "user_id" }))
public class MessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Message.DeliveryStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MessageReceipt() {
        // JPA
    }

    public MessageReceipt(String messageId, String userId, Message.DeliveryStatus status, Instant updatedAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.status = status;
        this.updatedAt = updatedAt;
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

    public Message.DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(Message.DeliveryStatus status) {
        this.status = status;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
