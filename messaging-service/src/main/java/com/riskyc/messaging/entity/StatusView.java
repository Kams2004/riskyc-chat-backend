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
 * One row per (status, viewer) — "seen by" tracking, visible only to the
 * poster (see StatusController#viewers), same WhatsApp-style privacy model
 * as read receipts but scoped to a single poster rather than broadcast to
 * the whole conversation.
 */
@Entity
@Table(name = "status_view", uniqueConstraints = @UniqueConstraint(columnNames = { "status_id", "viewer_id" }))
public class StatusView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status_id", nullable = false)
    private String statusId;

    @Column(name = "viewer_id", nullable = false)
    private String viewerId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    protected StatusView() {
        // JPA
    }

    public StatusView(String statusId, String viewerId, Instant viewedAt) {
        this.statusId = statusId;
        this.viewerId = viewerId;
        this.viewedAt = viewedAt;
    }

    public Long getId() {
        return id;
    }

    public String getStatusId() {
        return statusId;
    }

    public String getViewerId() {
        return viewerId;
    }

    public Instant getViewedAt() {
        return viewedAt;
    }
}
