package com.riskyc.messaging.dto;

/** fromDeviceId — see CallInvite's doc comment; relayed as-is, not auth-relevant. */
public record CallAnswer(String callId, String fromUserId, String sdpAnswer, String fromDeviceId) {
}
