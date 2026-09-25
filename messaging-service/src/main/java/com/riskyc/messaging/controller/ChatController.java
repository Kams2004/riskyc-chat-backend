package com.riskyc.messaging.controller;

import com.riskyc.common.dto.AttachmentDto;
import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.GroupAckRequest;
import com.riskyc.messaging.dto.GroupReceiptUpdate;
import com.riskyc.messaging.dto.MessageDeleteRequest;
import com.riskyc.messaging.dto.MessageEditRequest;
import com.riskyc.messaging.dto.MessageMutation;
import com.riskyc.messaging.dto.MessagePinRequest;
import com.riskyc.messaging.dto.MessageStatusUpdate;
import com.riskyc.messaging.dto.ReactionRequest;
import com.riskyc.messaging.dto.ReactionUpdate;
import com.riskyc.messaging.dto.TypingIndicator;
import com.riskyc.messaging.dto.TypingUpdate;
import com.riskyc.messaging.entity.DisappearingMessageSettings;
import com.riskyc.messaging.entity.GroupConversation;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.entity.MessageAttachment;
import com.riskyc.messaging.entity.MessageDeletion;
import com.riskyc.messaging.entity.MessageReaction;
import com.riskyc.messaging.entity.MessageReceipt;
import com.riskyc.messaging.repository.DisappearingMessageSettingsRepository;
import com.riskyc.messaging.repository.GroupConversationRepository;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageAttachmentRepository;
import com.riskyc.messaging.repository.MessageDeletionRepository;
import com.riskyc.messaging.repository.MessageReactionRepository;
import com.riskyc.messaging.repository.MessageReceiptRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.repository.MutedConversationRepository;
import com.riskyc.messaging.service.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final MessageRepository messageRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupConversationRepository groupConversationRepository;
    private final MessageReceiptRepository receiptRepository;
    private final MessageDeletionRepository messageDeletionRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final MutedConversationRepository mutedConversationRepository;
    private final DisappearingMessageSettingsRepository disappearingMessageSettingsRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;

    public ChatController(MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                           GroupConversationRepository groupConversationRepository, MessageReceiptRepository receiptRepository,
                           MessageDeletionRepository messageDeletionRepository, MessageAttachmentRepository messageAttachmentRepository,
                           MessageReactionRepository messageReactionRepository,
                           MutedConversationRepository mutedConversationRepository,
                           DisappearingMessageSettingsRepository disappearingMessageSettingsRepository,
                           SimpMessagingTemplate messagingTemplate, PushNotificationService pushNotificationService) {
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupConversationRepository = groupConversationRepository;
        this.receiptRepository = receiptRepository;
        this.messageDeletionRepository = messageDeletionRepository;
        this.messageAttachmentRepository = messageAttachmentRepository;
        this.messageReactionRepository = messageReactionRepository;
        this.mutedConversationRepository = mutedConversationRepository;
        this.disappearingMessageSettingsRepository = disappearingMessageSettingsRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
    }

    @MessageMapping("/chat.send")
    public void send(MessageEnvelope inbound) {
        boolean isGroup = inbound.groupId() != null;

        // Announcement-group enforcement. STOMP handlers here are
        // fire-and-forget with no response channel, so there's nothing to
        // reject back to the client — the real UX guard is the client hiding
        // the composer for non-admins; this is pure defense-in-depth that
        // should be unreachable in normal use, hence a silent drop + log
        // rather than any client-visible error.
        if (isGroup) {
            boolean onlyAdmins = groupConversationRepository.findById(inbound.groupId())
                    .map(GroupConversation::isOnlyAdminsCanMessage).orElse(false);
            if (onlyAdmins) {
                GroupMember.Role senderRole = groupMemberRepository
                        .findByGroupIdAndUserId(inbound.groupId(), inbound.senderId())
                        .map(GroupMember::getRole).orElse(null);
                if (senderRole != GroupMember.Role.ADMIN) {
                    log.warn("Dropped message from non-admin {} into announcement-only group {}",
                            inbound.senderId(), inbound.groupId());
                    return;
                }
            }
        }

        String messageId = inbound.messageId() != null ? inbound.messageId() : UUID.randomUUID().toString();
        Instant sentAt = inbound.sentAt() != null ? inbound.sentAt() : Instant.now();

        Message message = new Message(messageId, inbound.conversationId(), inbound.senderId(),
                isGroup ? inbound.groupId() : inbound.recipientId(), inbound.ciphertext(), sentAt);
        if (isGroup) {
            message.setGroupId(inbound.groupId());
        }
        message.setReplyToMessageId(inbound.replyToMessageId());
        message.setReplyToConversationId(inbound.replyToConversationId());
        message.setReplyToSenderId(inbound.replyToSenderId());
        message.setReplyToSnippet(inbound.replyToSnippet());
        message.setReplyToStatusId(inbound.replyToStatusId());
        message.setReplyToStatusOwnerId(inbound.replyToStatusOwnerId());
        if (inbound.mediaType() != null) {
            message.setMediaType(Message.MediaType.valueOf(inbound.mediaType()));
            message.setMediaObjectKey(inbound.mediaObjectKey());
            message.setMediaFileName(inbound.mediaFileName());
            message.setMediaDurationMs(inbound.mediaDurationMs());
            message.setWaveform(inbound.waveform());
            message.setOverlayJson(inbound.overlayJson());
            message.setMediaFileSize(inbound.mediaFileSize());
        }
        // A forward IS a send (same validation/broadcast/push-notification
        // logic, just a fresh messageId in a possibly different
        // conversation) — no separate endpoint, just this one extra flag.
        message.setForwarded(inbound.forwarded());
        // Stamped once, at send time, from whatever the conversation's
        // disappearing-messages duration is right now — a later change to
        // that setting never retroactively touches already-sent messages,
        // same as WhatsApp.
        disappearingMessageSettingsRepository.findById(inbound.conversationId())
                .ifPresent(s -> message.setExpiresAt(sentAt.plusSeconds(s.getDurationSeconds())));
        messageRepository.save(message);

        // Multi-attachment (gallery) send: a message never uses both this
        // and the scalar mediaType/mediaObjectKey path (see MessageEnvelope's
        // own doc comment) — inbound.mediaType() is null whenever attachments
        // are present, so message's own scalar columns stay untouched.
        List<AttachmentDto> attachmentDtos = List.of();
        if (inbound.attachments() != null && !inbound.attachments().isEmpty()) {
            List<MessageAttachment> rows = new ArrayList<>();
            List<AttachmentDto> dtos = new ArrayList<>();
            for (AttachmentDto a : inbound.attachments()) {
                rows.add(new MessageAttachment(messageId, a.position(), Message.MediaType.valueOf(a.mediaType()),
                        a.mediaObjectKey(), a.mediaFileName(), a.mediaDurationMs(), a.mediaFileSize(), a.overlayJson()));
                dtos.add(a);
            }
            messageAttachmentRepository.saveAll(rows);
            attachmentDtos = dtos;
        }

        MessageEnvelope outbound = new MessageEnvelope(messageId, inbound.conversationId(),
                inbound.senderId(), message.getRecipientId(), inbound.ciphertext(), sentAt,
                message.getStatus().name(), inbound.mediaType(), inbound.mediaObjectKey(),
                inbound.mediaFileName(), inbound.mediaDurationMs(), inbound.waveform(), inbound.overlayJson(), false, false, inbound.groupId(),
                inbound.forwarded(), attachmentDtos, inbound.replyToMessageId(), inbound.replyToConversationId(),
                inbound.replyToSenderId(), inbound.replyToSnippet(), false, null,
                inbound.senderDisplayName(), message.getExpiresAt(), false,
                inbound.replyToStatusId(), inbound.replyToStatusOwnerId(), inbound.mediaFileSize());
        messagingTemplate.convertAndSend("/topic/conversation." + inbound.conversationId(), outbound);

        String previewBody = previewFor(inbound.mediaType(), inbound.ciphertext(), attachmentDtos.size());
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
                // Muted only skips the push notification, never the in-app
                // delivery above — a muted chat still updates its unread
                // count and message list, same as WhatsApp, it just doesn't
                // bang/buzz the device.
                if (!mutedConversationRepository.existsByUserIdAndConversationId(memberId, inbound.groupId())) {
                    pushNotificationService.sendToUser(memberId, groupName, previewBody, "messages-v3", pushData);
                }
            }
        } else {
            messagingTemplate.convertAndSendToUser(inbound.recipientId(), "/queue/messages", outbound);
            String pushTitle = inbound.senderDisplayName() != null ? inbound.senderDisplayName() : "RiskyC Chat";
            if (!mutedConversationRepository.existsByUserIdAndConversationId(inbound.recipientId(), inbound.conversationId())) {
                pushNotificationService.sendToUser(inbound.recipientId(), pushTitle, previewBody, "messages-v3", pushData);
            }
        }
    }

    /**
     * No sender-name lookup here on purpose: that would mean an HTTP call
     * from messaging-service into auth-service on every single message
     * send, just for notification cosmetics. Kept simple for now — see
     * backend/README.md's other documented MVP-stage gaps.
     */
    private String previewFor(String mediaType, String ciphertext, int attachmentCount) {
        if (attachmentCount > 1) {
            return "📷 " + attachmentCount + " photos";
        }
        if (mediaType != null) {
            return switch (mediaType) {
                case "IMAGE" -> "📷 Photo";
                case "VIDEO" -> "🎥 Video";
                case "AUDIO" -> "🎤 Voice message";
                case "FILE" -> "📎 Document";
                case "STICKER" -> "Sticker";
                default -> "New message";
            };
        }
        if (ciphertext == null || ciphertext.isBlank()) {
            return "New message";
        }
        return ciphertext.length() > 120 ? ciphertext.substring(0, 117) + "..." : ciphertext;
    }

    /**
     * Ephemeral — no persistence. Two deliveries: the per-conversation topic
     * (whoever currently has this exact thread open) AND each recipient's
     * personal /queue/typing (so the chat LIST can show "typing…" even when
     * that thread isn't the open screen — mirrors how /queue/messages
     * already does this for new messages). userId comes from the STOMP
     * Principal, never the client payload, so one user can't fake another's
     * typing state.
     */
    @MessageMapping("/chat.typing")
    public void typing(TypingIndicator inbound, Principal principal) {
        if (principal == null) {
            return;
        }
        String senderId = principal.getName();
        TypingUpdate update = new TypingUpdate(inbound.conversationId(), senderId, inbound.isTyping());
        messagingTemplate.convertAndSend("/topic/conversation." + inbound.conversationId() + ".typing", update);

        if (!groupMemberRepository.findByGroupId(inbound.conversationId()).isEmpty()) {
            for (String memberId : otherMemberIds(inbound.conversationId(), senderId)) {
                messagingTemplate.convertAndSendToUser(memberId, "/queue/typing", update);
            }
            return;
        }

        // Not a group — a 1:1 conversationId is the two user ids, sorted and
        // joined with '_' (see mobile/web's conversationIdFor), so the other
        // party is whichever half isn't the sender.
        String[] parts = inbound.conversationId().split("_", 2);
        if (parts.length == 2) {
            String otherUserId = parts[0].equals(senderId) ? parts[1] : parts[0];
            messagingTemplate.convertAndSendToUser(otherUserId, "/queue/typing", update);
        }
    }

    @MessageMapping("/chat.ack")
    public void ack(MessageStatusUpdate update, Principal principal) {
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
        // Mirrors this ack to the ACKING user's own other devices (not the
        // sender's — the topic broadcast above already covers whoever sent
        // the message) so a device sitting on the conversation list, not
        // this specific thread, can still update its local unread badge live
        // instead of only on next full re-fetch. Same convertAndSendToUser
        // pattern already used for /chat.send's inbox fan-out.
        if (principal != null) {
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/read-state", update);
        }
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
            GroupReceiptUpdate receiptUpdate = new GroupReceiptUpdate(request.conversationId(), messageId, userId, receipt.getStatus().name());
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".receipts", receiptUpdate);
            // Same cross-device mirroring as /chat.ack — see its comment.
            messagingTemplate.convertAndSendToUser(userId, "/queue/read-state", receiptUpdate);
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

    /** A message can no longer be edited once it's this old — same spirit as WhatsApp's own editing window. STOMP handlers here have no response channel (see this controller's class doc comment), so the client is expected to hide the Edit option itself once a message crosses this age; this is the defensive backend enforcement, same pattern as onlyAdminsCanMessage above. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(2);

    @MessageMapping("/chat.edit")
    public void edit(MessageEditRequest request, Principal principal) {
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            if (principal == null || !principal.getName().equals(message.getSenderId())) {
                return;
            }
            if (message.getSentAt().plus(EDIT_WINDOW).isBefore(Instant.now())) {
                log.warn("Dropped edit for message {}: past the {}-hour edit window", request.messageId(), EDIT_WINDOW.toHours());
                return;
            }
            message.setCiphertext(request.newCiphertext());
            message.setEdited(true);
            messageRepository.save(message);
            MessageMutation mutation = new MessageMutation(request.conversationId(), request.messageId(), request.newCiphertext(), true, false, message.isPinned());
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".mutations", mutation);
            pushMutationToInboxes(message, mutation);
        });
    }

    /**
     * Toggle a message's shared pin state — any participant, not just the
     * sender (unlike /chat.edit), since pin is a per-conversation bookmark,
     * not an authorship right. Mirrors /chat.edit's shape: load by id, flip
     * the field, save, broadcast a mutation on the existing
     * /topic/conversation.{id}.mutations channel and mirror it into
     * /queue/mutations, reusing the already-built mutation pipeline rather
     * than standing up a new one.
     */
    @MessageMapping("/chat.pin")
    public void pin(MessagePinRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            message.setPinned(request.pinned());
            messageRepository.save(message);
            MessageMutation mutation = new MessageMutation(request.conversationId(), request.messageId(),
                    message.getCiphertext(), message.isEdited(), message.isDeleted(), message.isPinned());
            messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".mutations", mutation);
            pushMutationToInboxes(message, mutation);
        });
    }

    /**
     * Same emoji the caller already reacted with → removes it (a toggle).
     * A different emoji → replaces it. Only ever one reaction per (message,
     * user) — see MessageReaction's own doc comment. userId comes from the
     * STOMP Principal, never the client payload, so one participant can't
     * react on another's behalf.
     */
    @MessageMapping("/chat.react")
    public void react(ReactionRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        String userId = principal.getName();
        Optional<MessageReaction> existing = messageReactionRepository.findByMessageIdAndUserId(request.messageId(), userId);

        String resultingEmoji;
        if (existing.isPresent() && existing.get().getEmoji().equals(request.emoji())) {
            messageReactionRepository.deleteByMessageIdAndUserId(request.messageId(), userId);
            resultingEmoji = null;
        } else if (existing.isPresent()) {
            existing.get().setEmoji(request.emoji());
            messageReactionRepository.save(existing.get());
            resultingEmoji = request.emoji();
        } else {
            messageReactionRepository.save(new MessageReaction(request.messageId(), userId, request.emoji(), Instant.now()));
            resultingEmoji = request.emoji();
        }

        ReactionUpdate update = new ReactionUpdate(request.messageId(), userId, resultingEmoji);
        messagingTemplate.convertAndSend("/topic/conversation." + request.conversationId() + ".reactions", update);
    }

    /**
     * "EVERYONE" is sender-only and broadcasts to every other participant
     * (unchanged from before this DTO gained scope). "ME" is available to
     * ANY participant — including the recipient of someone else's message —
     * writes a MessageDeletion row for the caller only, and is deliberately
     * NEVER broadcast: it's invisible to everyone else by design. userId
     * comes from the STOMP Principal, never the client payload, so one
     * participant can't delete-for-me on another's behalf.
     */
    @MessageMapping("/chat.delete")
    public void delete(MessageDeleteRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            if ("ME".equals(request.scope())) {
                if (!messageDeletionRepository.existsByMessageIdAndUserId(request.messageId(), principal.getName())) {
                    messageDeletionRepository.save(new MessageDeletion(request.messageId(), principal.getName(), Instant.now()));
                }
                return;
            }
            if (!principal.getName().equals(message.getSenderId())) {
                return;
            }
            message.setDeleted(true);
            messageRepository.save(message);
            // Now redundant — everyone loses the message anyway, so any
            // earlier per-user delete-for-me markers for it serve no purpose.
            messageDeletionRepository.deleteByMessageId(request.messageId());
            MessageMutation mutation = new MessageMutation(request.conversationId(), request.messageId(), null, false, true, message.isPinned());
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
