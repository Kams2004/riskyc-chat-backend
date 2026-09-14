package com.riskyc.messaging.dto;

import java.util.List;

/**
 * Client -> server: "mark these messages as delivered/read". Server rebroadcasts
 * the same shape to /topic/conversation.{id}.status so the original sender's
 * client can flip its own copies' ticks — this is how the sender ever learns a
 * message was delivered or read, since that only happens on the recipient's device.
 */
public record MessageStatusUpdate(String conversationId, List<String> messageIds, String status) {
}
