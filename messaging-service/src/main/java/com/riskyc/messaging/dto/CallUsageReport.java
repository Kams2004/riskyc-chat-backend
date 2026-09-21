package com.riskyc.messaging.dto;

/** Each side's own accumulated WebRTC bytesSent/bytesReceived, reported independently at call teardown — see CallController#reportUsage. */
public record CallUsageReport(String callId, long bytesSent, long bytesReceived) {
}
