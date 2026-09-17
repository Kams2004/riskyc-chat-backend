package com.riskyc.messaging.service;

import com.riskyc.messaging.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Hard-deletes expired disappearing messages — the second of two enforcement
 * layers (MessageHistoryController#history already filters them out of any
 * read the instant they expire, so this job's only job is to actually free
 * the data, not to keep them hidden; a slightly-late sweep here is never a
 * correctness problem for what a client sees).
 */
@Component
public class DisappearingMessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DisappearingMessageCleanupJob.class);

    private final MessageRepository messageRepository;

    public DisappearingMessageCleanupJob(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        int deleted = messageRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Hard-deleted {} expired disappearing message(s)", deleted);
        }
    }
}
