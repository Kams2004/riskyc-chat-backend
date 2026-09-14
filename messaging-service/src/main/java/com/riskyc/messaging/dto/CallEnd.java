package com.riskyc.messaging.dto;

/** reason is one of "declined", "cancelled", "hangup" (client's intent) — the server derives the persisted CallStatus from it plus whether the call was ever answered. */
public record CallEnd(String callId, String fromUserId, String reason) {
}
