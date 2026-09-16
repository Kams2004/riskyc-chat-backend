package com.riskyc.messaging.dto;

/**
 * Delivered to each invited member's existing /user/queue/calls destination
 * with STOMP header callMessageType=group-invite — same destination/
 * discrimination pattern CallInvite already uses for 1:1 calls, just fanned
 * out to N members instead of 1. roomId is always equal to groupId (see
 * sfu-service's room model — a group's id doubles as both its conversationId
 * AND its call room id), included for symmetry with CallInvite's callId so
 * client code can treat both shapes uniformly where useful.
 */
public record GroupCallInvite(String roomId, String groupId, String callerId, String callerName, String callType) {
}
