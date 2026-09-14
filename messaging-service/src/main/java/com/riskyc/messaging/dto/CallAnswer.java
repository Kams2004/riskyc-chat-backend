package com.riskyc.messaging.dto;

public record CallAnswer(String callId, String fromUserId, String sdpAnswer) {
}
