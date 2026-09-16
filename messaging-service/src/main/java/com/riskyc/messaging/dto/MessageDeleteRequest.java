package com.riskyc.messaging.dto;

/** {@code scope} is "EVERYONE" (sender-only, broadcast) or "ME" (any participant, never broadcast) — see ChatController#delete. */
public record MessageDeleteRequest(String conversationId, String messageId, String scope) {
}
