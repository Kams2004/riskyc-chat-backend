package com.riskyc.auth.repository;

import com.riskyc.auth.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    List<Session> findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(UUID userId);

    Optional<Session> findByJti(String jti);

    /** Polled by messaging-service's RevokedJtiCache — only jtis revoked since the last poll need to be (re-)learned. */
    List<Session> findByRevokedTrueAndRevokedAtAfter(Instant since);

    /**
     * True iff this user currently has at least one non-revoked session —
     * false is exactly the condition under which the NEXT session created
     * for them becomes master (AuthController#verifyOtp): either this is
     * the account's first-ever login, or a previous master cascade-revoked
     * every session and nobody has logged back in yet.
     */
    boolean existsByUserIdAndRevokedFalse(UUID userId);
}
