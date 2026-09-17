package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.dto.ConversationSummary;
import com.riskyc.messaging.entity.DisappearingMessageSettings;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.entity.MutedConversation;
import com.riskyc.messaging.repository.DisappearingMessageSettingsRepository;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.repository.MutedConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciliation endpoint for the mobile client's local-first conversation
 * list, which is otherwise built entirely from live STOMP events
 * (ChatController's convertAndSendToUser fan-out). That works fine while the
 * app is connected, but a message that arrives while the recipient's socket
 * is disconnected (backgrounded past the OS grace period, killed, a fresh
 * install/account) previously just vanished from that device's view —
 * nothing ever told it the conversation existed at all, until the user
 * manually re-found the sender and started a new thread with them. Called
 * once on every socket (re)connect (see mobile's inboxSocket.ts) to backfill
 * any conversation the device doesn't know about yet.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final MessageRepository messageRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MutedConversationRepository mutedConversationRepository;
    private final DisappearingMessageSettingsRepository disappearingMessageSettingsRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public ConversationController(MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                                   MutedConversationRepository mutedConversationRepository,
                                   DisappearingMessageSettingsRepository disappearingMessageSettingsRepository,
                                   SimpMessagingTemplate messagingTemplate, JwtIssuer jwtIssuer,
                                   RevokedJtiCache revokedJtiCache) {
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.mutedConversationRepository = mutedConversationRepository;
        this.disappearingMessageSettingsRepository = disappearingMessageSettingsRepository;
        this.messagingTemplate = messagingTemplate;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    @GetMapping
    public List<ConversationSummary> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        List<ConversationSummary> summaries = new ArrayList<>();

        Map<String, DisappearingMessageSettings> disappearingById = new HashMap<>();
        for (DisappearingMessageSettings s : disappearingMessageSettingsRepository.findAll()) {
            disappearingById.put(s.getConversationId(), s);
        }
        Set<String> mutedIds = new HashSet<>();
        for (MutedConversation m : mutedConversationRepository.findByUserId(userId)) {
            mutedIds.add(m.getConversationId());
        }

        for (MessageRepository.OneToOneSummaryRow row : messageRepository.findOneToOneSummariesForUser(userId)) {
            DisappearingMessageSettings s = disappearingById.get(row.getConversationId());
            summaries.add(new ConversationSummary(row.getConversationId(), row.getOtherUserId(), null,
                    row.getLastMessageAt().toString(), mutedIds.contains(row.getConversationId()),
                    s != null ? s.getDurationSeconds() : null));
        }

        for (GroupMember member : groupMemberRepository.findByUserId(userId)) {
            Instant lastMessageAt = messageRepository.findLastMessageAtForGroup(member.getGroupId());
            DisappearingMessageSettings s = disappearingById.get(member.getGroupId());
            summaries.add(new ConversationSummary(member.getGroupId(), null, member.getGroupId(),
                    (lastMessageAt != null ? lastMessageAt : member.getJoinedAt()).toString(),
                    mutedIds.contains(member.getGroupId()), s != null ? s.getDurationSeconds() : null));
        }

        return summaries;
    }

    public record ConversationSettingsResult(boolean muted, Integer disappearingMessageSeconds) {
    }

    /** Single-conversation counterpart to list()'s bulk sync — the thread screen's own initial fetch for mute/disappearing state, rather than searching the whole list for one entry. */
    @GetMapping("/{conversationId}/settings")
    public ConversationSettingsResult settings(@PathVariable String conversationId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        boolean muted = mutedConversationRepository.existsByUserIdAndConversationId(userId, conversationId);
        Integer seconds = disappearingMessageSettingsRepository.findById(conversationId)
                .map(DisappearingMessageSettings::getDurationSeconds)
                .orElse(null);
        return new ConversationSettingsResult(muted, seconds);
    }

    public record MuteRequest(boolean muted) {
    }

    /** Server-side (not a client-local flag) so ChatController#send can skip this user's push before it's ever sent — see MutedConversation's own doc comment. */
    @PutMapping("/{conversationId}/mute")
    public void setMuted(@PathVariable String conversationId, @RequestBody MuteRequest request,
                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        if (request.muted()) {
            if (!mutedConversationRepository.existsByUserIdAndConversationId(userId, conversationId)) {
                mutedConversationRepository.save(new MutedConversation(userId, conversationId, Instant.now()));
            }
        } else {
            mutedConversationRepository.deleteByUserIdAndConversationId(userId, conversationId);
        }
    }

    public record DisappearingRequest(Integer seconds) {
    }

    public record DisappearingChanged(String conversationId, Integer seconds, String changedBy) {
    }

    /**
     * seconds=null turns disappearing messages off. Shared per conversation
     * (any participant, no admin gate — same looser-than-WhatsApp precedent
     * already set by the pinned-message feature), applies only to messages
     * sent from now on. Broadcasts live so every open thread — including the
     * setter's own other devices — updates immediately without a reload.
     */
    @PutMapping("/{conversationId}/disappearing")
    public void setDisappearing(@PathVariable String conversationId, @RequestBody DisappearingRequest request,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        if (request.seconds() == null || request.seconds() <= 0) {
            disappearingMessageSettingsRepository.deleteById(conversationId);
        } else {
            disappearingMessageSettingsRepository.save(new DisappearingMessageSettings(conversationId, request.seconds(), userId));
        }
        messagingTemplate.convertAndSend("/topic/conversation." + conversationId + ".settings",
                new DisappearingChanged(conversationId, request.seconds(), userId));
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
