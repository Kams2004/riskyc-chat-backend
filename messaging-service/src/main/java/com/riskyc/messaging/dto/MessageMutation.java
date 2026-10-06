package com.riskyc.messaging.dto;

/**
 * Broadcast on /topic/conversation.{id}.mutations whenever a message's own
 * sender edits or deletes it (see ChatController#edit / #delete) — separate
 * from the delivery-status topic since this changes the message's *content*,
 * not who has seen it. {@code ciphertext} is null for a delete.
 *
 * {@code inviteStatus} is a second, unrelated use of this same mutation
 * pipeline: null for every ordinary edit/delete/pin, and set (to
 * PENDING/ACCEPTED/DECLINED/EXPIRED) only when a GROUP_INVITE card message's
 * status changes — see GroupInvitationMessageService. Reusing this channel
 * rather than the /queue/messages send pipeline avoids the invitee's own
 * accept/decline action being mistaken for a brand-new inbound message
 * (unread badge, notification sound) on either side.
 */
public record MessageMutation(String conversationId, String messageId, String ciphertext, boolean edited, boolean deleted, boolean pinned, String inviteStatus) {
}
