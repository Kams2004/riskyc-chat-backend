package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.dto.ConversationSummary;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public ConversationController(MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                                   JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    @GetMapping
    public List<ConversationSummary> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        List<ConversationSummary> summaries = new ArrayList<>();

        for (MessageRepository.OneToOneSummaryRow row : messageRepository.findOneToOneSummariesForUser(userId)) {
            summaries.add(new ConversationSummary(row.getConversationId(), row.getOtherUserId(), null,
                    row.getLastMessageAt().toString()));
        }

        for (GroupMember member : groupMemberRepository.findByUserId(userId)) {
            Instant lastMessageAt = messageRepository.findLastMessageAtForGroup(member.getGroupId());
            summaries.add(new ConversationSummary(member.getGroupId(), null, member.getGroupId(),
                    (lastMessageAt != null ? lastMessageAt : member.getJoinedAt()).toString()));
        }

        return summaries;
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
