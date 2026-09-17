package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.MutedConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MutedConversationRepository extends JpaRepository<MutedConversation, Long> {
    Optional<MutedConversation> findByUserIdAndConversationId(String userId, String conversationId);

    boolean existsByUserIdAndConversationId(String userId, String conversationId);

    /** Feeds the client's own "which of my conversations are muted" sync on load. */
    List<MutedConversation> findByUserId(String userId);

    @Modifying
    @Transactional
    void deleteByUserIdAndConversationId(String userId, String conversationId);
}
