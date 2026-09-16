package com.riskyc.messaging.dto;

import java.time.Instant;
import java.util.List;

/** Body of POST /internal/group-calls/ended — see GroupCallController. */
public record GroupCallEndedRequest(String groupId, String callType, Instant startedAt, Instant endedAt, List<String> participantIds) {
}
