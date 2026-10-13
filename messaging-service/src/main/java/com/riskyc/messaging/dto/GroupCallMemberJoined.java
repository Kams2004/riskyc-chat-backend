package com.riskyc.messaging.dto;

/**
 * Sent to a group-call member's OWN user destination the instant sfu-service
 * reports they actually joined the SFU room (see
 * GroupCallController#memberJoined) — reaches every other device that
 * account is signed into, so a sibling device still showing the incoming-
 * group-call banner for this groupId can dismiss it, mirroring 1:1's
 * "answered-elsewhere" CallEnd signal.
 */
public record GroupCallMemberJoined(String groupId, String memberId) {
}
