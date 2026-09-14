package com.riskyc.messaging.dto;

import java.util.List;

public record GroupAckRequest(String conversationId, List<String> messageIds, String status) {
}
