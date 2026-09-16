package com.riskyc.messaging.dto;

import java.util.List;

/** Body of POST /internal/group-calls/invite — see GroupCallController. */
public record GroupCallInviteRequest(String groupId, String callerId, String callerName, List<String> memberIds, String callType) {
}
