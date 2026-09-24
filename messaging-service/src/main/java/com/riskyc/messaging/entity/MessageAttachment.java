package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One row per attachment on a message that carries more than one image/video
 * (a WhatsApp-style gallery send) — a brand-new table rather than a column
 * added to the already-populated `message` table, so ddl-auto: update can
 * create it with NOT NULL columns risk-free (the "NOT NULL column on a
 * populated table" migration failure this session already hit once only
 * applies to altering an existing table, not creating a new one). A message
 * with a single attachment keeps using Message's own scalar media columns
 * unchanged — this table is only ever populated for the 2+-attachment case.
 */
@Entity
@Table(name = "message_attachment", indexes = {
        @Index(name = "idx_message_attachment_message", columnList = "message_id, position")
})
public class MessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private Message.MediaType mediaType;

    @Column(name = "media_object_key", nullable = false)
    private String mediaObjectKey;

    @Column(name = "media_file_name")
    private String mediaFileName;

    @Column(name = "media_duration_ms")
    private Integer mediaDurationMs;

    // Bytes — client-supplied at send time (see AttachmentDto's own field
    // comment). Feeds the combined-size download gate shown before a
    // multi-item gallery has actually been fetched.
    @Column(name = "media_file_size")
    private Long mediaFileSize;

    protected MessageAttachment() {
        // JPA
    }

    public MessageAttachment(String messageId, int position, Message.MediaType mediaType, String mediaObjectKey,
                              String mediaFileName, Integer mediaDurationMs, Long mediaFileSize) {
        this.messageId = messageId;
        this.position = position;
        this.mediaType = mediaType;
        this.mediaObjectKey = mediaObjectKey;
        this.mediaFileName = mediaFileName;
        this.mediaDurationMs = mediaDurationMs;
        this.mediaFileSize = mediaFileSize;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public int getPosition() {
        return position;
    }

    public Message.MediaType getMediaType() {
        return mediaType;
    }

    public String getMediaObjectKey() {
        return mediaObjectKey;
    }

    public String getMediaFileName() {
        return mediaFileName;
    }

    public Integer getMediaDurationMs() {
        return mediaDurationMs;
    }

    public Long getMediaFileSize() {
        return mediaFileSize;
    }
}
