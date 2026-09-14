package com.riskyc.messaging.dto;

/** Broadcast on /topic/conversation.{id}.typing — works for both 1:1 and group threads alike. */
public record TypingUpdate(String conversationId, String userId, boolean isTyping) {
}
