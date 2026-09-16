package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.MessageDeletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MessageDeletionRepository extends JpaRepository<MessageDeletion, Long> {
    boolean existsByMessageIdAndUserId(String messageId, String userId);

    List<MessageDeletion> findByUserIdAndMessageIdIn(String userId, List<String> messageIds);

    // Derived delete queries need their own transaction — a plain
    // @Controller/@RestController method has none by default (see
    // GroupMemberRepository's identical comment on this exact gotcha).
    @Modifying
    @Transactional
    void deleteByMessageId(String messageId);
}
