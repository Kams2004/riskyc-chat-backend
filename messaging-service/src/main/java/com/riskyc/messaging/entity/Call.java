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

    // Each side reports its OWN totals independently at teardown (see
    // CallController#reportUsage) — the two are never assumed symmetric.
    // Video-heavy content, packet loss/retransmits, and which side is
    // sending vs. mostly receiving can all make one party's bytes look
    // very different from the other's, which is exactly why the call log
    // shows both rather than one shared number. Null until that side
    // actually reports (e.g. a call that never connected has nothing to
    // report and stays null, not zero).
    @Column(name = "caller_bytes_sent")
    private Long callerBytesSent;

    @Column(name = "caller_bytes_received")
    private Long callerBytesReceived;

    @Column(name = "callee_bytes_sent")
    private Long calleeBytesSent;

    @Column(name = "callee_bytes_received")
    private Long calleeBytesReceived;

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

    public Long getCallerBytesSent() {
        return callerBytesSent;
    }

    public void setCallerBytesSent(Long callerBytesSent) {
        this.callerBytesSent = callerBytesSent;
    }

    public Long getCallerBytesReceived() {
        return callerBytesReceived;
    }

    public void setCallerBytesReceived(Long callerBytesReceived) {
        this.callerBytesReceived = callerBytesReceived;
    }

    public Long getCalleeBytesSent() {
        return calleeBytesSent;
    }

    public void setCalleeBytesSent(Long calleeBytesSent) {
        this.calleeBytesSent = calleeBytesSent;
    }

    public Long getCalleeBytesReceived() {
        return calleeBytesReceived;
    }

    public void setCalleeBytesReceived(Long calleeBytesReceived) {
        this.calleeBytesReceived = calleeBytesReceived;
    }
}
