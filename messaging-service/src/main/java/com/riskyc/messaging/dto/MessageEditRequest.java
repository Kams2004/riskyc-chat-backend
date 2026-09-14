package com.riskyc.messaging.dto;

public record MessageEditRequest(String conversationId, String messageId, String newCiphertext) {
}
