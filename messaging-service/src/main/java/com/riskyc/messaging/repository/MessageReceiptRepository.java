package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.MessageReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageReceiptRepository extends JpaRepository<MessageReceipt, Long> {
    Optional<MessageReceipt> findByMessageIdAndUserId(String messageId, String userId);

    List<MessageReceipt> findByMessageId(String messageId);
}
