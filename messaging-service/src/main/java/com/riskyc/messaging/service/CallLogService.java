package com.riskyc.messaging.service;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.entity.Call;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.MessageRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Drops a call summary into the conversation's message history — same table,
 * same delivery paths (topic broadcast + both parties' inbox queues) as a
 * real message, so it shows up inline with no separate subscription or
 * rendering plumbing needed beyond mobile checking for mediaType "CALL".
 * mediaFileName carries the call type (AUDIO/VIDEO, repurposed — there's no
 * dedicated column for it) and ciphertext carries the outcome
 * (ENDED/MISSED/DECLINED); mediaDurationMs is the real talk time (0 if the
 * call was never answered).
 *
 * Shared between CallController (a client explicitly ending the call) and
 * CallTimeoutJob (nobody ever did — see that class for why that path exists
 * at all) so both go through the exact same message shape.
 */
@Service
public class CallLogService {

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public CallLogService(MessageRepository messageRepository, SimpMessagingTemplate messagingTemplate) {
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void logCallAsMessage(Call call) {
        String conversationId = call.getCallerId().compareTo(call.getCalleeId()) < 0
                ? call.getCallerId() + "_" + call.getCalleeId()
                : call.getCalleeId() + "_" + call.getCallerId();
        long durationMs = call.getAnsweredAt() != null
                ? Duration.between(call.getAnsweredAt(), call.getEndedAt()).toMillis()
                : 0;

        Message callLog = new Message(UUID.randomUUID().toString(), conversationId, call.getCallerId(),
                call.getCalleeId(), call.getStatus().name(), call.getStartedAt());
        callLog.setMediaType(Message.MediaType.CALL);
        callLog.setMediaFileName(call.getType().name());
        callLog.setMediaDurationMs((int) durationMs);
        messageRepository.save(callLog);

        MessageEnvelope envelope = new MessageEnvelope(callLog.getMessageId(), conversationId, call.getCallerId(),
                call.getCalleeId(), callLog.getCiphertext(), callLog.getSentAt(), callLog.getStatus().name(),
                "CALL", null, callLog.getMediaFileName(), callLog.getMediaDurationMs(), null, null, false, false, null, false,
                java.util.List.of(), null, null, null, null, false, null, null, null, false, null, null, null);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, envelope);
        messagingTemplate.convertAndSendToUser(call.getCallerId(), "/queue/messages", envelope);
        messagingTemplate.convertAndSendToUser(call.getCalleeId(), "/queue/messages", envelope);
    }
}
