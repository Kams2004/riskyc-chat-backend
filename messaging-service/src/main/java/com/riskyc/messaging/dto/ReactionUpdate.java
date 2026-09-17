package com.riskyc.messaging.dto;

/** Broadcast on /topic/conversation.{id}.reactions. emoji=null means userId removed their reaction. */
public record ReactionUpdate(String messageId, String userId, String emoji) {
}
