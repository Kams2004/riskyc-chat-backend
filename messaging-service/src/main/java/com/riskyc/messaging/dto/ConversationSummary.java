package com.riskyc.messaging.dto;

/** One row per conversation (1:1 or group) this user has ever exchanged a message in — see ConversationController. */
public record ConversationSummary(String conversationId, String otherUserId, String groupId, String lastMessageAt,
                                   boolean muted, Integer disappearingMessageSeconds) {
}
