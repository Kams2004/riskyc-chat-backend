package com.riskyc.messaging.dto;

/**
 * Sent by the caller to /app/call.invite; broadcast (with fromUserId
 * attached server-side from the STOMP Principal, never client-supplied) to
 * the callee's /user/queue/calls. callerName is client-supplied (messaging-
 * service has no User table to look it up itself) — used only for the push
 * notification's title and the notification-answer flow's display; never
 * trusted for anything security-relevant.
 */
public record CallInvite(String callId, String fromUserId, String toUserId, String type, String sdpOffer, String callerName) {
}
