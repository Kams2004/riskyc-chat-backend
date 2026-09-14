package com.riskyc.messaging.dto;

/** Inbound /chat.typing payload — userId is never trusted from the client, see ChatController#typing. */
public record TypingIndicator(String conversationId, boolean isTyping) {
}
