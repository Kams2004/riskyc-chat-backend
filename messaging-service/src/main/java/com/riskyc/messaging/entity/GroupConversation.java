package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A group's id doubles as its conversationId — the same
 * /topic/conversation.{id} broadcast mechanism 1:1 chats use already fans out
 * to every subscriber regardless of how many there are, so no broker changes
 * were needed to support groups.
 */
@Entity
@Table(name = "group_conversation")
public class GroupConversation {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "avatar_object_key")
    private String avatarObjectKey;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupConversation() {
        // JPA
    }

    public GroupConversation(String id, String name, String createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatarObjectKey() {
        return avatarObjectKey;
    }

    public void setAvatarObjectKey(String avatarObjectKey) {
        this.avatarObjectKey = avatarObjectKey;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
