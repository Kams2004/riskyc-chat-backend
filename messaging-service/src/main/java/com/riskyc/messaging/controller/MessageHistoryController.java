package com.riskyc.messaging.controller;

import com.riskyc.common.dto.AttachmentDto;
import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.entity.MessageAttachment;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageAttachmentRepository;
import com.riskyc.messaging.repository.MessageDeletionRepository;
import com.riskyc.messaging.repository.MessageReactionRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/messages")
public class MessageHistoryController {

    private final MessageRepository messageRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MessageDeletionRepository messageDeletionRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public MessageHistoryController(MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                                     MessageDeletionRepository messageDeletionRepository,
                                     MessageAttachmentRepository messageAttachmentRepository,
                                     MessageReactionRepository messageReactionRepository, JwtIssuer jwtIssuer,
                                     RevokedJtiCache revokedJtiCache) {
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.messageDeletionRepository = messageDeletionRepository;
        this.messageReactionRepository = messageReactionRepository;
        this.messageAttachmentRepository = messageAttachmentRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record MediaSummaryItem(String messageId, String mediaType, String mediaObjectKey, String mediaFileName,
                                    java.time.Instant sentAt) {
    }

    public record SearchResult(String messageId, String senderId, String ciphertext, java.time.Instant sentAt) {
    }

    /**
     * Previously had NO auth or membership check at all — anyone who knew or
     * guessed a conversationId could read its full history. 1:1 threads use
     * a deterministic "{userIdA}_{userIdB}" id (sorted, see mobile's
     * conversationId.ts), so membership there is just "am I one of the two
     * halves"; a group thread's conversationId IS the groupId, so membership
     * is a normal GroupMember lookup.
     */
    @GetMapping("/{conversationId}")
    public List<MessageEnvelope> history(@PathVariable String conversationId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        requireMembership(conversationId, callerId);

        List<Message> messages = messageRepository.findByConversationIdOrderBySentAtAsc(conversationId);
        List<String> messageIds = messages.stream().map(Message::getMessageId).toList();
        Set<String> deletedForMe = messageDeletionRepository.findByUserIdAndMessageIdIn(callerId, messageIds).stream()
                .map(d -> d.getMessageId())
                .collect(Collectors.toSet());
        Map<String, List<AttachmentDto>> attachmentsByMessage = messageAttachmentRepository
                .findByMessageIdInOrderByPosition(messageIds).stream()
                .collect(Collectors.groupingBy(MessageAttachment::getMessageId,
                        Collectors.mapping(this::toAttachmentDto, Collectors.toList())));

        Instant now = Instant.now();
        return messages.stream()
                .filter(m -> !deletedForMe.contains(m.getMessageId()))
                // Correct immediately even before the scheduled sweep
                // (DisappearingMessageCleanupJob) actually deletes the row —
                // see Message.expiresAt's own doc comment.
                .filter(m -> m.getExpiresAt() == null || m.getExpiresAt().isAfter(now))
                .map(m -> new MessageEnvelope(m.getMessageId(), m.getConversationId(), m.getSenderId(),
                        m.getRecipientId(), m.getCiphertext(), m.getSentAt(), m.getStatus().name(),
                        m.getMediaType() != null ? m.getMediaType().name() : null,
                        m.getMediaObjectKey(), m.getMediaFileName(), m.getMediaDurationMs(),
                        m.isEdited(), m.isDeleted(), m.getGroupId(), m.isForwarded(),
                        attachmentsByMessage.getOrDefault(m.getMessageId(), List.of()),
                        m.getReplyToMessageId(), m.getReplyToConversationId(), m.getReplyToSenderId(),
                        m.getReplyToSnippet(), m.isPinned(), m.getMediaParticipantCount(),
                        null, m.getExpiresAt()))
                .toList();
    }

    /** Feeds the contact-details "Media, links, and docs" preview/sub-page — scoped to this conversation only. */
    @GetMapping("/{conversationId}/media-summary")
    public List<MediaSummaryItem> mediaSummary(@PathVariable String conversationId,
                                                @RequestParam(defaultValue = "IMAGE,VIDEO,FILE") String types,
                                                @RequestParam(defaultValue = "50") int limit,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        requireMembership(conversationId, callerId);
        List<Message.MediaType> mediaTypes = List.of(types.split(",")).stream().map(Message.MediaType::valueOf).toList();

        List<MediaSummaryItem> fromMessages = messageRepository
                .findByConversationIdAndMediaTypeInAndDeletedFalseOrderBySentAtDesc(conversationId, mediaTypes).stream()
                .map(m -> new MediaSummaryItem(m.getMessageId(), m.getMediaType().name(), m.getMediaObjectKey(),
                        m.getMediaFileName(), m.getSentAt()))
                .toList();

        List<String> messageIds = messageRepository.findByConversationIdOrderBySentAtAsc(conversationId).stream()
                .map(Message::getMessageId).toList();
        List<MediaSummaryItem> fromAttachments = messageAttachmentRepository
                .findByMessageIdInOrderByPosition(messageIds).stream()
                .filter(a -> mediaTypes.contains(a.getMediaType()))
                .map(a -> new MediaSummaryItem(a.getMessageId(), a.getMediaType().name(), a.getMediaObjectKey(),
                        a.getMediaFileName(), null))
                .toList();

        return java.util.stream.Stream.concat(fromMessages.stream(), fromAttachments.stream())
                .limit(limit)
                .toList();
    }

    /**
     * Scoped to one conversation, ILIKE against ciphertext — viable only
     * because ciphertext is still plaintext today (no E2E encryption yet,
     * see MessageEnvelope's own doc comment); this becomes meaningless once
     * that ships and would need to move to per-device local search instead.
     */
    @GetMapping("/{conversationId}/search")
    public List<SearchResult> search(@PathVariable String conversationId, @RequestParam String q,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        requireMembership(conversationId, callerId);
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return messageRepository.searchInConversation(conversationId, q).stream()
                .map(m -> new SearchResult(m.getMessageId(), m.getSenderId(), m.getCiphertext(), m.getSentAt()))
                .toList();
    }

    public record ReactionRow(String messageId, String userId, String emoji) {
    }

    /** Bulk, one call per conversation open — feeds initial reaction state; live updates arrive over /topic/conversation.{id}.reactions afterward (see ChatController#react). */
    @GetMapping("/{conversationId}/reactions")
    public List<ReactionRow> reactions(@PathVariable String conversationId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        requireMembership(conversationId, callerId);
        List<String> messageIds = messageRepository.findByConversationIdOrderBySentAtAsc(conversationId).stream()
                .map(Message::getMessageId)
                .toList();
        return messageReactionRepository.findByMessageIdIn(messageIds).stream()
                .map(r -> new ReactionRow(r.getMessageId(), r.getUserId(), r.getEmoji()))
                .toList();
    }

    private AttachmentDto toAttachmentDto(MessageAttachment a) {
        return new AttachmentDto(a.getPosition(), a.getMediaType().name(), a.getMediaObjectKey(), a.getMediaFileName(),
                a.getMediaDurationMs());
    }

    private void requireMembership(String conversationId, String callerId) {
        String[] parts = conversationId.split("_");
        boolean isOneToOnePair = parts.length == 2 && (parts[0].equals(callerId) || parts[1].equals(callerId));
        if (isOneToOnePair) {
            return;
        }
        boolean isGroupMember = groupMemberRepository.findByGroupIdAndUserId(conversationId, callerId).isPresent();
        if (isGroupMember) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant in this conversation");
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(authorization.substring("Bearer ".length()));
            if (revokedJtiCache.isRevoked(claims.jti())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session has been signed out");
            }
            return claims.subject();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
