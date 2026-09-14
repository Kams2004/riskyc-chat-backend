package com.riskyc.messaging.dto;

public record CallIceCandidate(String callId, String fromUserId, String candidate, String sdpMid, Integer sdpMLineIndex) {
}
