package com.riskyc.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A 1:1 call — keyed purely by the two participants, not tied to any
 * conversationId (matches how a call doesn't require an existing chat).
 */
@Entity
@Table(name = "call")
public class Call {

    public enum CallType { AUDIO, VIDEO }

    public enum CallStatus { RINGING, ACCEPTED, DECLINED, MISSED, ENDED }

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "caller_id", nullable = false)
    private String callerId;

    @Column(name = "callee_id", nullable = false)
    private String calleeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private CallType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CallStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    // Set at invite time, cleared once answered/ended — lets a notification
    // tap fetch a fresh offer via GET /api/calls/{id} without putting the SDP
    // itself in the push payload (size limits, and it'd go stale if the
    // caller's ICE gathering produces a different offer before the callee
    // actually taps Answer).
    @Column(name = "sdp_offer", columnDefinition = "text")
    private String sdpOffer;

    // Same "client-supplied, display-only" caveat as CallInvite#callerName —
    // messaging-service has no User table of its own to resolve this itself.
    @Column(name = "caller_name")
    private String callerName;

    protected Call() {
        // JPA
    }

    public Call(String id, String callerId, String calleeId, CallType type, Instant startedAt) {
        this.id = id;
        this.callerId = callerId;
        this.calleeId = calleeId;
        this.type = type;
        this.status = CallStatus.RINGING;
        this.startedAt = startedAt;
    }

    public String getId() {
        return id;
    }

    public String getCallerId() {
        return callerId;
    }

    public String getCalleeId() {
        return calleeId;
    }

    public CallType getType() {
        return type;
    }

    public CallStatus getStatus() {
        return status;
    }

    public void setStatus(CallStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    /** Whoever didn't place the call. */
    public String otherParty(String userId) {
        return userId.equals(callerId) ? calleeId : callerId;
    }

    public String getSdpOffer() {
        return sdpOffer;
    }

    public void setSdpOffer(String sdpOffer) {
        this.sdpOffer = sdpOffer;
    }

    public String getCallerName() {
        return callerName;
    }

    public void setCallerName(String callerName) {
        this.callerName = callerName;
    }
}
