package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {
    Optional<MessageReaction> findByMessageIdAndUserId(String messageId, String userId);

    /** Feeds a freshly-opened thread's initial reaction state — see MessageHistoryController#reactions. */
    List<MessageReaction> findByMessageIdIn(Collection<String> messageIds);

    @Modifying
    @Transactional
    void deleteByMessageIdAndUserId(String messageId, String userId);
}
