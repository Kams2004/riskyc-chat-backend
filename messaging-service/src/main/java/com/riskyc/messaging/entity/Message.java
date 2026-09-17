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

    // Stored via @Enumerated(EnumType.STRING) below (a varchar column, not a
    // native Postgres enum type) — adding VIDEO is a zero-migration change.
    // Was missing entirely until now despite ChatController.previewFor
    // already having a dead "VIDEO" case that could never actually fire.
    public enum MediaType { IMAGE, VIDEO, FILE, AUDIO, CALL }

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

    // Only ever set on a group-call log entry (mediaType=CALL, groupId
    // non-null) — how many people were in the call, so the client can render
    // "Group call · 4 people · 12m" without needing a separate lookup. Null
    // for every other message, including a 1:1 call log (which already
    // implies exactly 2 participants).
    @Column(name = "media_participant_count")
    private Integer mediaParticipantCount;

    @Column(name = "edited", nullable = false)
    private boolean edited = false;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    // Marker only — WhatsApp-style "Forwarded" label, no back-reference to
    // the original message. Deliberately no forwardedFromMessageId: deleting
    // the original (delete-for-everyone) must never orphan a FK on a
    // message that was forwarded from it.
    //
    // columnDefinition spells out an explicit default: ddl-auto=update's
    // plain "ADD COLUMN forwarded boolean not null" (no default) fails
    // outright against a table that already has rows ("column contains
    // null values") since Postgres has nothing to backfill existing rows
    // with — this is what actually breaks a NOT NULL column added to an
    // already-populated table, not a hypothetical.
    @Column(name = "forwarded", nullable = false, columnDefinition = "boolean not null default false")
    private boolean forwarded = false;

    // Null for a 1:1 message. When set, this message belongs to a group
    // conversation — recipientId is unused in that case (there's no single
    // recipient), left populated with the groupId itself as a harmless
    // placeholder rather than relaxing recipientId's NOT NULL constraint.
    @Column(name = "group_id")
    private String groupId;

    // All four null for a message that isn't a reply. Generated client-side
    // at send time from the sender's own locally-rendered content, not
    // re-derived server-side — this is what lets "reply privately" work:
    // that reply lands in a DIFFERENT conversation than the original
    // message, so the recipient's client needs enough context to render the
    // quote without a local join.
    @Column(name = "reply_to_message_id")
    private String replyToMessageId;

    @Column(name = "reply_to_conversation_id")
    private String replyToConversationId;

    @Column(name = "reply_to_sender_id")
    private String replyToSenderId;

    @Column(name = "reply_to_snippet")
    private String replyToSnippet;

    // Shared, per-conversation pin — any participant can toggle it, no admin
    // gate, no expiry. Deliberately a smaller subset of WhatsApp's real pin
    // system (single pin surfaced via one banner, not a whole pinned list).
    @Column(name = "pinned", nullable = false, columnDefinition = "boolean not null default false")
    private boolean pinned = false;

    // Null for a normal message. Set at send time (sentAt + the
    // conversation's disappearing-message duration, see
    // DisappearingMessageSettingsRepository) to the instant this row should
    // stop being visible. Two enforcement layers, same pattern as revoked
    // JWTs elsewhere in this codebase: history reads filter out anything
    // already past expiresAt (correct immediately, no window where an
    // expired message is still visible), and a @Scheduled sweep hard-deletes
    // expired rows periodically (actually frees the data, not just hides it).
    @Column(name = "expires_at")
    private Instant expiresAt;

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

    public Integer getMediaParticipantCount() {
        return mediaParticipantCount;
    }

    public void setMediaParticipantCount(Integer mediaParticipantCount) {
        this.mediaParticipantCount = mediaParticipantCount;
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

    public boolean isForwarded() {
        return forwarded;
    }

    public void setForwarded(boolean forwarded) {
        this.forwarded = forwarded;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(String replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public String getReplyToConversationId() {
        return replyToConversationId;
    }

    public void setReplyToConversationId(String replyToConversationId) {
        this.replyToConversationId = replyToConversationId;
    }

    public String getReplyToSenderId() {
        return replyToSenderId;
    }

    public void setReplyToSenderId(String replyToSenderId) {
        this.replyToSenderId = replyToSenderId;
    }

    public String getReplyToSnippet() {
        return replyToSnippet;
    }

    public void setReplyToSnippet(String replyToSnippet) {
        this.replyToSnippet = replyToSnippet;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
