package com.riskyc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per issued JWT — the source of truth for the "logged-in devices"
 * screen and for remote sign-out. jti is the token's own JWT ID claim (see
 * JwtIssuer); revoking a session here doesn't invalidate the JWT's signature
 * (it's still cryptographically valid until natural expiry) — it only stops
 * being *trusted* by whichever service checks RevokedJtiCache/this table.
 * See auth-service's own README-documented scope: enforced here and in
 * messaging-service; media-service/presence-service stay signature-only
 * trust (both are Postgres-free, so checking this table isn't cheap there).
 */
@Entity
@Table(name = "session", uniqueConstraints = @UniqueConstraint(columnNames = "jti"))
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "jti", nullable = false)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_label")
    private String deviceLabel;

    // "ios" | "android" | "web" | "system" — drives the one-active-mobile-
    // session-per-account rule in AuthController#verifyOtp (web is exempt by
    // design; "system" is the system-account short-circuit login, also
    // exempt). Client-supplied like deviceLabel, but unlike deviceLabel this
    // one IS trusted for something security-relevant, so treat any value
    // outside the four above as non-mobile rather than guessing.
    @Column(name = "platform")
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    // Separate from lastSeenAt (which tracks actual use, not revocation) —
    // this is what messaging-service's RevokedJtiCache polls against to find
    // only newly-revoked sessions since its last poll.
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Session() {
        // JPA
    }

    public Session(String jti, UUID userId, String deviceLabel, Instant createdAt, String platform) {
        this.jti = jti;
        this.userId = userId;
        this.deviceLabel = deviceLabel;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
        this.platform = platform;
    }

    public UUID getId() {
        return id;
    }

    public String getJti() {
        return jti;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public String getPlatform() {
        return platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke(Instant now) {
        this.revoked = true;
        this.revokedAt = now;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
