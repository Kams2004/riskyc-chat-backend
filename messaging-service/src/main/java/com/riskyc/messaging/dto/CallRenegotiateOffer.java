package com.riskyc.messaging.dto;

/** ICE-restart renegotiation mid-call — see CallController#renegotiate. Same relay pattern as CallInvite, minus the fields only a fresh invite needs (toUserId/type/callerName): the Call row already has those. */
public record CallRenegotiateOffer(String callId, String fromUserId, String sdpOffer) {
}
