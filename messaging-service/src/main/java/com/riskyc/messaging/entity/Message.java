package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "message", indexes = {
        @Index(name = "idx_message_conversation", columnList = "conversation_id, sent_at")
})
public class Message {

    public enum DeliveryStatus { SENT, DELIVERED, READ }

    public enum MediaType { IMAGE, FILE, AUDIO, CALL }

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    // Plain TEXT, deliberately not @Lob: on Postgres, Hibernate maps a @Lob
    // String to a Large Object (an OID pointer into pg_largeobject), whose
    // streaming API requires a non-autocommit transaction — reading it back
    // through a plain repository call throws "Large Objects may not be used
    // in auto-commit mode." @Column(columnDefinition) keeps it a normal
    // column, which handles values of this size (message bodies, not files).
    // Doubles as a media message's caption — empty string, never null, when
    // there isn't one.
    @Column(name = "ciphertext", nullable = false, columnDefinition = "text")
    private String ciphertext;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status = DeliveryStatus.SENT;

    // All null for a plain-text message; the object itself (image/file/audio
    // bytes) lives in MinIO under mediaObjectKey, same presigned-URL pipeline
    // as avatars — this table only ever stores the pointer.
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type")
    private MediaType mediaType;

    @Column(name = "media_object_key")
    private String mediaObjectKey;

    @Column(name = "media_file_name")
    private String mediaFileName;

    @Column(name = "media_duration_ms")
    private Integer mediaDurationMs;

    @Column(name = "edited", nullable = false)
    private boolean edited = false;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    // Null for a 1:1 message. When set, this message belongs to a group
    // conversation — recipientId is unused in that case (there's no single
    // recipient), left populated with the groupId itself as a harmless
    // placeholder rather than relaxing recipientId's NOT NULL constraint.
    @Column(name = "group_id")
    private String groupId;

    protected Message() {
        // JPA
    }

    public Message(String messageId, String conversationId, String senderId, String recipientId,
                    String ciphertext, Instant sentAt) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.ciphertext = ciphertext;
        this.sentAt = sentAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    public String getMediaObjectKey() {
        return mediaObjectKey;
    }

    public void setMediaObjectKey(String mediaObjectKey) {
        this.mediaObjectKey = mediaObjectKey;
    }

    public String getMediaFileName() {
        return mediaFileName;
    }

    public void setMediaFileName(String mediaFileName) {
        this.mediaFileName = mediaFileName;
    }

    public Integer getMediaDurationMs() {
        return mediaDurationMs;
    }

    public void setMediaDurationMs(Integer mediaDurationMs) {
        this.mediaDurationMs = mediaDurationMs;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
