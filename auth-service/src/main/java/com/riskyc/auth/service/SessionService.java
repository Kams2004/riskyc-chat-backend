package com.riskyc.auth.service;

import com.riskyc.auth.entity.Session;
import com.riskyc.auth.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared "revoke every linked device" operation — used both when the master
 * device signs itself out (SessionController#revoke cascades, since the
 * account is never left masterless for long: the next login re-earns master,
 * see AuthController#verifyOtp) and when the account itself is deleted
 * (UserController#deleteMe), so every orphaned Session row is cleaned up
 * rather than left pointing at a now-nonexistent user.
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void revokeAll(UUID userId) {
        Instant now = Instant.now();
        sessionRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(userId)
                .forEach(session -> {
                    session.revoke(now);
                    sessionRepository.save(session);
                });
    }
}
