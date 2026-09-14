package com.riskyc.messaging.dto;

/**
 * Sent by the caller to /app/call.invite; broadcast (with fromUserId
 * attached server-side from the STOMP Principal, never client-supplied) to
 * the callee's /user/queue/calls.
 */
public record CallInvite(String callId, String fromUserId, String toUserId, String type, String sdpOffer) {
}
