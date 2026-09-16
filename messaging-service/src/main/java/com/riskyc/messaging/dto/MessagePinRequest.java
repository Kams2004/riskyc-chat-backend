package com.riskyc.messaging.dto;

public record MessagePinRequest(String conversationId, String messageId, boolean pinned) {
}
