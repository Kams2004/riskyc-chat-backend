package com.riskyc.messaging.service;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.messaging.dto.MessageMutation;
import com.riskyc.messaging.entity.GroupInvitation;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.MessageRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Posts and updates the GROUP_INVITE card message that represents a group
 * invitation inside the inviter/invitee's 1:1 conversation — the UI surface
 * for invitations (accept/decline/expiry) lives entirely in that chat
 * bubble, not a dedicated screen (see GroupController and
 * GroupInvitationExpiryJob, both of which call into this one place so the
 * card-message logic isn't duplicated between them).
 */
@Service
public class GroupInvitationMessageService {

    /** Sentinel Message.ciphertext for a GROUP_INVITE card — never shown raw, same convention as GroupController.SYSTEM_MEMBER_JOINED. */
    private static final String INVITE_SENTINEL = "__GROUP_INVITE__";

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupInvitationMessageService(MessageRepository messageRepository, SimpMessagingTemplate messagingTemplate) {
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /** Same sorted-pair convention as mobile/web's conversationIdFor — deterministic regardless of who initiates. */
    public static String oneToOneConversationId(String userA, String userB) {
        return userA.compareTo(userB) <= 0 ? userA + "_" + userB : userB + "_" + userA;
    }

    /**
     * Creates the invite card, persists it, and pushes it to both the
     * inviter and the invitee — unlike a normal /chat.send, NEITHER side
     * already has this message locally (it's synthesized by a REST call
     * that only returns a GroupResult), so both need the broadcast, not just
     * "the other party" the way chat.send's self-already-has-it convention
     * works.
     */
    public void postInviteMessage(GroupInvitation invitation, String groupName, String groupAvatarObjectKey) {
        String conversationId = oneToOneConversationId(invitation.getInviterId(), invitation.getInviteeId());
        String messageId = UUID.randomUUID().toString();
        Instant sentAt = Instant.now();

        Message message = new Message(messageId, conversationId, invitation.getInviterId(), invitation.getInviteeId(), INVITE_SENTINEL, sentAt);
        message.setMediaType(Message.MediaType.GROUP_INVITE);
        message.setInviteGroupId(invitation.getGroupId());
        message.setInviteGroupName(groupName);
        message.setInviteGroupAvatarObjectKey(groupAvatarObjectKey);
        message.setInviteInvitationId(invitation.getId());
        message.setInviteStatus(GroupInvitation.Status.PENDING);
        messageRepository.save(message);

        MessageEnvelope envelope = toEnvelope(message);
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId, envelope);
        messagingTemplate.convertAndSendToUser(invitation.getInviterId(), "/queue/messages", envelope);
        messagingTemplate.convertAndSendToUser(invitation.getInviteeId(), "/queue/messages", envelope);
    }

    /**
     * Called when an invitation is accepted, declined, or swept into
     * EXPIRED. The invitee is always the one acting (or the scheduled sweep,
     * which neither party "acts" on) — the invitee's own client applies the
     * new status to its local copy immediately rather than waiting on this
     * broadcast (same "the acting side already knows" convention as
     * /chat.send's own recipient-only fan-out), so this only needs to reach
     * the INVITER, via the existing edit/delete/pin mutation channel.
     */
    public void updateInviteMessageStatus(Long invitationId, GroupInvitation.Status newStatus) {
        messageRepository.findFirstByInviteInvitationIdOrderBySentAtDesc(invitationId).ifPresent(message -> {
            message.setInviteStatus(newStatus);
            messageRepository.save(message);
            MessageMutation mutation = new MessageMutation(message.getConversationId(), message.getMessageId(),
                    message.getCiphertext(), message.isEdited(), message.isDeleted(), message.isPinned(), newStatus.name());
            messagingTemplate.convertAndSend("/topic/conversation." + message.getConversationId() + ".mutations", mutation);
            messagingTemplate.convertAndSendToUser(message.getSenderId(), "/queue/mutations", mutation);
        });
    }

    private MessageEnvelope toEnvelope(Message m) {
        return new MessageEnvelope(m.getMessageId(), m.getConversationId(), m.getSenderId(), m.getRecipientId(),
                m.getCiphertext(), m.getSentAt(), m.getStatus().name(), m.getMediaType().name(), null, null, null,
                null, null, false, false, null, false, List.of(), null, null, null, null, false, null, null,
                m.getExpiresAt(), false, null, null, null,
                m.getInviteGroupId(), m.getInviteGroupName(), m.getInviteGroupAvatarObjectKey(),
                m.getInviteInvitationId(), m.getInviteStatus().name());
    }
}
