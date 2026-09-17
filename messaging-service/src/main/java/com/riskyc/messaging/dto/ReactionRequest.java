package com.riskyc.messaging.dto;

/** Inbound /chat.react payload — userId comes from the STOMP Principal, never trusted from here. */
public record ReactionRequest(String messageId, String conversationId, String emoji) {
}
