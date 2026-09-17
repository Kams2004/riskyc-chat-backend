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
 * A pending (or resolved) invitation to join a group — added members are
 * never inserted into group_member directly any more; every add, whether at
 * group-creation time or later via GroupController#invite, goes through one
 * of these rows first, and only GroupController#accept actually creates the
 * GroupMember. One row per (group, invitee) — re-inviting after a DECLINED
 * response resets the same row back to PENDING rather than creating a
 * second one.
 */
@Entity
@Table(name = "group_invitation", uniqueConstraints = @UniqueConstraint(columnNames = { "group_id", "invitee_id" }))
public class GroupInvitation {

    public enum Status { PENDING, ACCEPTED, DECLINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "inviter_id", nullable = false)
    private String inviterId;

    @Column(name = "invitee_id", nullable = false)
    private String inviteeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected GroupInvitation() {
        // JPA
    }

    public GroupInvitation(String groupId, String inviterId, String inviteeId, Status status, Instant createdAt) {
        this.groupId = groupId;
        this.inviterId = inviterId;
        this.inviteeId = inviteeId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getInviterId() {
        return inviterId;
    }

    public void setInviterId(String inviterId) {
        this.inviterId = inviterId;
    }

    public String getInviteeId() {
        return inviteeId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}
