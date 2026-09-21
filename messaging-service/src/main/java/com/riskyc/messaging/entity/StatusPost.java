package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One WhatsApp-style status/story post. expiresAt is always createdAt+48h,
 * stamped once at creation — enforced the same two-layer way as message TTL
 * (see Message.expiresAt's own doc comment): feed/detail reads filter it
 * immediately, and StatusCleanupJob hard-deletes past it. mediaObjectKey
 * points at MinIO (same presigned-upload pipeline as message attachments)
 * and is null for a text-only status, which instead uses textContent +
 * backgroundColor. overlayJson is an opaque client-authored JSON blob
 * (freehand drawing strokes + an optional text-overlay label drawn on top
 * of the media) — stored and echoed back as-is, never parsed or validated
 * server-side, same "server doesn't understand it, just carries it" role as
 * ciphertext on a Message. Composited onto the media at VIEW time on the
 * client (an SVG overlay, not baked into the image's own pixels), so this
 * is always null for a TEXT-mediaType status, which needs no overlay.
 */
@Entity
@Table(name = "status_post", indexes = {
        @Index(name = "idx_status_post_user", columnList = "user_id, expires_at")
})
public class StatusPost {

    public enum MediaType { TEXT, IMAGE, VIDEO }

    @Id
    @Column(name = "status_id")
    private String statusId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    @Column(name = "media_object_key")
    private String mediaObjectKey;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "background_color")
    private String backgroundColor;

    @Column(name = "overlay_json", columnDefinition = "text")
    private String overlayJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected StatusPost() {
        // JPA
    }

    public StatusPost(String statusId, String userId, MediaType mediaType, String mediaObjectKey,
                       String textContent, String backgroundColor, String overlayJson, Instant createdAt, Instant expiresAt) {
        this.statusId = statusId;
        this.userId = userId;
        this.mediaType = mediaType;
        this.mediaObjectKey = mediaObjectKey;
        this.textContent = textContent;
        this.backgroundColor = backgroundColor;
        this.overlayJson = overlayJson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getStatusId() {
        return statusId;
    }

    public String getUserId() {
        return userId;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public String getMediaObjectKey() {
        return mediaObjectKey;
    }

    public String getTextContent() {
        return textContent;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public String getOverlayJson() {
        return overlayJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
