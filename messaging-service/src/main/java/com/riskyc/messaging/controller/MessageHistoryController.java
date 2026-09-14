package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.MessageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageHistoryController {

    private final MessageRepository messageRepository;

    public MessageHistoryController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping("/{conversationId}")
    public List<MessageEnvelope> history(@PathVariable String conversationId) {
        return messageRepository.findByConversationIdOrderBySentAtAsc(conversationId).stream()
                .map(m -> new MessageEnvelope(m.getMessageId(), m.getConversationId(), m.getSenderId(),
                        m.getRecipientId(), m.getCiphertext(), m.getSentAt(), m.getStatus().name(),
                        m.getMediaType() != null ? m.getMediaType().name() : null,
                        m.getMediaObjectKey(), m.getMediaFileName(), m.getMediaDurationMs(),
                        m.isEdited(), m.isDeleted(), m.getGroupId()))
                .toList();
    }
}
