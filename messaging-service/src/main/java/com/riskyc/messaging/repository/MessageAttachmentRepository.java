package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {
    List<MessageAttachment> findByMessageIdOrderByPosition(String messageId);

    List<MessageAttachment> findByMessageIdInOrderByPosition(List<String> messageIds);
}
