package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.GroupAckRequest;
import com.riskyc.messaging.dto.GroupReceiptUpdate;
import com.riskyc.messaging.dto.MessageDeleteRequest;
import com.riskyc.messaging.dto.MessageEditRequest;
import com.riskyc.messaging.dto.MessageMutation;
import com.riskyc.messaging.dto.MessageStatusUpdate;
import com.riskyc.messaging.dto.TypingIndicator;
import com.riskyc.messaging.dto.TypingUpdate;
import com.riskyc.messaging.entity.GroupConversation;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.entity.MessageReceipt;
import com.riskyc.messaging.repository.GroupConversationRepository;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageReceiptRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles inbound STOMP frames sent to /app/chat.send, /app/chat.ack,
 * /app/chat.ack.group, /app/chat.edit and /app/chat.delete. Broadcasts to
 * /topic/conversation.{conversationId}[.status|.mutations|.receipts] for
 * whichever client currently has that specific thread open, AND pushes new
 * messages/mutations directly to each recipient's (or, for a group, each
 * OTHER member's) /user/.../queue/... so mobile's app-wide InboxSocket picks
 * them up even when that conversation screen isn't open.
 */
@Controller
public class ChatController {

    private final MessageRepository messageRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupConversationRepository groupConversationRepository;
    private final MessageReceiptRepository receiptRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;

    public ChatController(MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                           GroupConversationRepository groupConversationRepository, MessageReceiptRepository receiptRepository,
                           SimpMessagingTemplate messagingTemplate, PushNotificationService pushNotificationService) {
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupConversationRepository = groupConversationRepository;
        this.receiptRepository = receiptRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
    }

    @MessageMapping("/chat.send")
    public void send(MessageEnvelope inbound) {
        String messageId = inbound.messageId() != null ? inbound.messageId() : UUID.randomUUID().toString();
        Instant sentAt = inbound.sentAt() != null ? inbound.sentAt() : Instant.now();
        boolean isGroup = inbound.groupId() != null;

        Message message = new Message(messageId, inbound.conversationId(), inbound.senderId(),
                isGroup ? inbound.groupId() : inbound.recipientId(), inbound.ciphertext(), sentAt);
        if (isGroup) {
            message.setGroupId(inbound.groupId());
        }
        if (inbound.mediaType() != null) {
            message.setMediaType(Message.MediaType.valueOf(inbound.mediaType()));
            message.setMediaObjectKey(inbound.mediaObjectKey());
            message.setMediaFileName(inbound.mediaFileName());
            message.setMediaDurationMs(inbound.mediaDurationMs());
        }
        messageRepository.save(message);

        MessageEnvelope outbound = new MessageEnvelope(messageId, inbound.conversationId(),
                inbound.senderId(), message.getRecipientId(), inbound.ciphertext(), sentAt,
                message.getStatus().name(), inbound.mediaType(), inbound.mediaObjectKey(),
                inbound.mediaFileName(), inbound.mediaDurationMs(), false, false, inbound.groupId());
        messagingTemplate.convertAndSend("/topic/conversation." + inbound.conversationId(), outbound);

        String previewBody = previewFor(inbound.mediaType(), inbound.ciphertext());
        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("type", "message");
        pushData.put("conversationId", inbound.conversationId());
        pushData.put("senderId", inbound.senderId());
        if (isGroup) {
            pushData.put("groupId", inbound.groupId());
        }

        if (isGroup) {
            String groupName = groupConversationRepository.findById(inbound.groupId())
                    .map(GroupConversation::getName).orElse("Group chat");
            for (String memberId : otherMemberIds(inbound.groupId(), inbound.senderId())) {
                messagingTemplate.convertAndSendToUser(memberId, "/queue/messages", outbound);
                pushNotificationService.sendToUser(memberId, groupName, previewBody, "messages", pushData);
            }
        } else {
            messagingTemplate.convertAndSendToUser(inbound.recipientId(), "/queue/messages", outbound);
            pushNotificationService.sendToUser(inbound.recipientId(), "RiskyC Chat", previewBody, "messages", pushData);
        }
    }

    /**
     * No sender-name lookup here on purpose: that would mean an HTTP call
     * from messaging-service into auth-service on every single message
     * send, just for notification cosmetics. Kept simple for now — see
     * backend/README.md's other documented MVP-stage gaps.
     */
    private String previewFor(String mediaType, String ciphertext) {
        if (mediaType != null) {
            return switch (mediaType) {
                case "IMAGE" -> "📷 Photo";
                case "VIDEO" -> "🎥 Video";
                case "AUDIO" -> "🎤 Voice message";
                case "FILE" -> "📎 Document";
                default -> "New message";
            };
        }
        if (ciphertext == null || ciphertext.isBlank()) {
            return "New message";
        }
        return ciphertext.length() > 120 ? ciphertext.substring(0, 117) + "..." : ciphertext;
    }

    /**
     * Ephemeral — no persistence, just a relay to whoever currently has this
     * thread open (works identically for 1:1 and group). userId comes from
     * the STOMP Principal, never the client payload, so one user can't fake
     * another's typing state.
     */
    @MessageMapping("/chat.typing")
    public void typing(TypingIndicator inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        TypingUpdate update = new TypingUpdate(inbound.conversationId(), principal.getName(), inbound.isTyping());
        messagingTemplate.convertAndSend("/topic/conversation." + inbound.conversationId() + ".typing", update);
    }

    @MessageMapping("/chat.ack")
    public void ack(MessageStatusUpdate update) {
        Message.DeliveryStatus newStatus = Message.DeliveryStatus.valueOf(update.status());
        for (String messageId : update.messageIds()) {
            messageRepository.findById(messageId).ifPresent(message -> {
                if (newStatus.ordinal() > message.getStatus().ordinal()) {
                    message.setStatus(newStatus);
                    messageRepository.save(message);
                }
            });
        }
        messagingTemplate.convertAndSend("/topic/conversation." + update.conversationId() + ".status", update);
    }

    /**
     * Group counterpart to /chat.ack — kept as a separate endpoint so the
     * existing 1:1 ack path above is untouched. Each member acks their OWN
     * receipt (from the STOMP Principal, not a client-supplied id), so one
     * member can't forge another's read status.
     */
    @MessageMapping("/chat.ack.group")
    public void ackGroup(GroupAckRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        String userId = principal.getName();
        Message.DeliveryStatus newStatus = Message.DeliveryStatus.valueOf(request.status());
        for (String messageId : request.messageIds()) {
            MessageReceipt receipt = receiptRepository.findByMessageIdAndUserId(messageId, userId)
                    .orElseGet(() -> new MessageReceipt(messageId, userId, Message.DeliveryStatus.SENT, Instant.now()));
            if (newStatus.ordinal() > receipt.getStatus().ordinal()) {
                receipt.setStatus(newStatus);
                receipt.setUpdatedAt(Instant.now());
                receiptRepository.save(receipt);
            }
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".receipts",
                    new GroupReceiptUpdate(request.conversationId(), messageId, userId, receipt.getStatus().name()));
            messageRepository.findById(messageId).ifPresent(this::recomputeGroupAggregateStatus);
        }
    }

    /**
     * A group message has no single status from an ack alone (see
     * MessageReceipt) — this folds every other member's current receipt back
     * into the same Message.status column a 1:1 message uses directly, so
     * GET /api/messages/{conversationId} (fetchHistory) is correct for a
     * group thread that isn't currently open to have received the live
     * /receipts broadcasts the mobile client also aggregates on its own.
     */
    private void recomputeGroupAggregateStatus(Message message) {
        if (message.getGroupId() == null) {
            return;
        }
        List<String> otherMembers = otherMemberIds(message.getGroupId(), message.getSenderId());
        if (otherMembers.isEmpty()) {
            return;
        }
        Message.DeliveryStatus aggregate = Message.DeliveryStatus.READ;
        for (String memberId : otherMembers) {
            Message.DeliveryStatus memberStatus = receiptRepository.findByMessageIdAndUserId(message.getMessageId(), memberId)
                    .map(MessageReceipt::getStatus)
                    .orElse(Message.DeliveryStatus.SENT);
            if (memberStatus.ordinal() < aggregate.ordinal()) {
                aggregate = memberStatus;
            }
        }
        if (aggregate != message.getStatus()) {
            message.setStatus(aggregate);
            messageRepository.save(message);
        }
    }

    @MessageMapping("/chat.edit")
    public void edit(MessageEditRequest request, Principal principal) {
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            if (principal == null || !principal.getName().equals(message.getSenderId())) {
                return;
            }
            message.setCiphertext(request.newCiphertext());
            message.setEdited(true);
            messageRepository.save(message);
            MessageMutation mutation = new MessageMutation(request.conversationId(), request.messageId(), request.newCiphertext(), true, false);
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".mutations", mutation);
            pushMutationToInboxes(message, mutation);
        });
    }

    @MessageMapping("/chat.delete")
    public void delete(MessageDeleteRequest request, Principal principal) {
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            if (principal == null || !principal.getName().equals(message.getSenderId())) {
                return;
            }
            message.setDeleted(true);
            messageRepository.save(message);
            MessageMutation mutation = new MessageMutation(request.conversationId(), request.messageId(), null, false, true);
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".mutations", mutation);
            pushMutationToInboxes(message, mutation);
        });
    }

    private void pushMutationToInboxes(Message message, MessageMutation mutation) {
        if (message.getGroupId() != null) {
            for (String memberId : otherMemberIds(message.getGroupId(), message.getSenderId())) {
                messagingTemplate.convertAndSendToUser(memberId, "/queue/mutations", mutation);
            }
        } else {
            messagingTemplate.convertAndSendToUser(message.getRecipientId(), "/queue/mutations", mutation);
        }
    }

    private List<String> otherMemberIds(String groupId, String excludingUserId) {
        return groupMemberRepository.findByGroupId(groupId).stream()
                .map(GroupMember::getUserId)
                .filter(id -> !id.equals(excludingUserId))
                .toList();
    }
}
