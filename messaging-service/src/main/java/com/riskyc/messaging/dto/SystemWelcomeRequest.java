package com.riskyc.messaging.dto;

/** Body for POST /internal/system-account/welcome — see auth-service's SystemAccountService for the caller side. */
public record SystemWelcomeRequest(String conversationId, String senderId, String senderDisplayName,
                                    String senderAvatarObjectKey, String recipientId, String text) {
}
