package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.AutoDownloadDisabled;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AutoDownloadDisabledRepository extends JpaRepository<AutoDownloadDisabled, Long> {
    boolean existsByUserIdAndConversationId(String userId, String conversationId);

    /** Feeds the client's own "which of my conversations have auto-download off" sync on load. */
    List<AutoDownloadDisabled> findByUserId(String userId);

    @Modifying
    @Transactional
    void deleteByUserIdAndConversationId(String userId, String conversationId);
}
