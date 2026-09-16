package com.riskyc.messaging.dto;

/**
 * Broadcast on /topic/conversation.{id}.mutations whenever a message's own
 * sender edits or deletes it (see ChatController#edit / #delete) — separate
 * from the delivery-status topic since this changes the message's *content*,
 * not who has seen it. {@code ciphertext} is null for a delete.
 */
public record MessageMutation(String conversationId, String messageId, String ciphertext, boolean edited, boolean deleted, boolean pinned) {
}
