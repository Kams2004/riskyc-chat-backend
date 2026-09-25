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
 * Auto-download-media OFF for one user, in one conversation — one row per
 * (user, conversation), same shape and same "presence of a row is the flag"
 * convention as MutedConversation. Absence of a row means auto-download
 * stays on (the default), matching how every existing conversation behaves
 * today with no migration/backfill needed for it. conversationId is the
 * same opaque string every message/history endpoint already uses (a 1:1's
 * sorted-pair id, or a groupId), so this table works for both without
 * needing to know which kind it is — though the client currently only
 * surfaces this toggle from group details, per the request that prompted it.
 */
@Entity
@Table(name = "auto_download_disabled", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "conversation_id" }))
public class AutoDownloadDisabled {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "disabled_at", nullable = false)
    private Instant disabledAt;

    protected AutoDownloadDisabled() {
        // JPA
    }

    public AutoDownloadDisabled(String userId, String conversationId, Instant disabledAt) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.disabledAt = disabledAt;
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

    public Instant getDisabledAt() {
        return disabledAt;
    }
}
