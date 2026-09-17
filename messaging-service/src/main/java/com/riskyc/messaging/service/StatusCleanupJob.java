package com.riskyc.messaging.service;

import com.riskyc.messaging.repository.StatusPostRepository;
import com.riskyc.messaging.repository.StatusViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Hard-deletes status posts (and their view rows) past their 24h expiry —
 * second of two enforcement layers, mirroring DisappearingMessageCleanupJob.
 * StatusController's feed/detail reads already filter expiresAt themselves,
 * so a slightly-late sweep here never lets an expired status stay visible.
 */
@Component
public class StatusCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StatusCleanupJob.class);

    private final StatusPostRepository statusPostRepository;
    private final StatusViewRepository statusViewRepository;

    public StatusCleanupJob(StatusPostRepository statusPostRepository, StatusViewRepository statusViewRepository) {
        this.statusPostRepository = statusPostRepository;
        this.statusViewRepository = statusViewRepository;
    }

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        List<String> expiredIds = statusPostRepository.findExpiredIds(Instant.now());
        if (expiredIds.isEmpty()) {
            return;
        }
        statusViewRepository.deleteByStatusIdIn(expiredIds);
        statusPostRepository.deleteByStatusIdIn(expiredIds);
        log.info("Hard-deleted {} expired status post(s)", expiredIds.size());
    }
}
