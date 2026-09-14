package com.riskyc.messaging.dto;

/** Broadcast on /topic/conversation.{id}.receipts whenever one group member's own receipt for one message advances. */
public record GroupReceiptUpdate(String conversationId, String messageId, String userId, String status) {
}
