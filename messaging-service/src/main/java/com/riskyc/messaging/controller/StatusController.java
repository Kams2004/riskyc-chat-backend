package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.entity.StatusPost;
import com.riskyc.messaging.entity.StatusView;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.repository.StatusPostRepository;
import com.riskyc.messaging.repository.StatusViewRepository;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WhatsApp-style Status/Stories. The feed is deliberately computed from
 * EXISTING conversation relationships (1:1 partners + shared-group members)
 * rather than from the client's device contact list, so this feature never
 * needs the client to (re-)send its contacts to the server — keeping the
 * privacy commitment already published in web's Permissions page ("We never
 * store your full contact list on our servers") intact. A status is only
 * ever visible to someone who is a "contact" by that definition; there's no
 * separate per-status audience picker (matches WhatsApp's default "My
 * contacts" audience — the narrower "Contacts except..."/"Only share
 * with..." lists aren't implemented).
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final long STATUS_TTL_HOURS = 48;

    private final StatusPostRepository statusPostRepository;
    private final StatusViewRepository statusViewRepository;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public StatusController(StatusPostRepository statusPostRepository, StatusViewRepository statusViewRepository,
                             MessageRepository messageRepository, GroupMemberRepository groupMemberRepository,
                             SimpMessagingTemplate messagingTemplate, JwtIssuer jwtIssuer,
                             RevokedJtiCache revokedJtiCache) {
        this.statusPostRepository = statusPostRepository;
        this.statusViewRepository = statusViewRepository;
        this.messageRepository = messageRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.messagingTemplate = messagingTemplate;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record CreateStatusRequest(StatusPost.MediaType mediaType, String mediaObjectKey, String textContent,
                                       String backgroundColor, String overlayJson) {
    }

    public record StatusItem(String statusId, String userId, StatusPost.MediaType mediaType, String mediaObjectKey,
                              String textContent, String backgroundColor, String overlayJson, String createdAt,
                              String expiresAt, boolean viewedByMe) {
    }

    public record StatusFeedEntry(String userId, List<StatusItem> statuses, boolean hasUnviewed) {
    }

    @PostMapping
    public StatusItem create(@RequestBody CreateStatusRequest request,
                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        if (request.mediaType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType is required");
        }
        if (request.mediaType() == StatusPost.MediaType.TEXT && (request.textContent() == null || request.textContent().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "textContent is required for a text status");
        }
        if (request.mediaType() != StatusPost.MediaType.TEXT && (request.mediaObjectKey() == null || request.mediaObjectKey().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaObjectKey is required for a media status");
        }

        Instant now = Instant.now();
        StatusPost post = new StatusPost(UUID.randomUUID().toString(), userId, request.mediaType(),
                request.mediaObjectKey(), request.textContent(), request.backgroundColor(), request.overlayJson(), now,
                now.plus(STATUS_TTL_HOURS, ChronoUnit.HOURS));
        statusPostRepository.save(post);

        StatusItem item = toItem(post, true);
        for (String contactId : contactsOf(userId)) {
            messagingTemplate.convertAndSendToUser(contactId, "/queue/status", item);
        }
        return item;
    }

    /** One entry per contact who has an active status, ordered so unviewed ones surface first — mirrors WhatsApp's status list ordering. */
    @GetMapping("/feed")
    public List<StatusFeedEntry> feed(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = callerIdFrom(authorization);
        Set<String> contactIds = contactsOf(userId);
        if (contactIds.isEmpty()) {
            return List.of();
        }

        List<StatusPost> posts = statusPostRepository.findByUserIdInAndExpiresAtAfterOrderByCreatedAtAsc(contactIds, Instant.now());
        Set<String> viewedStatusIds = new HashSet<>();
        for (StatusView v : statusViewRepository.findByStatusIdInAndViewerId(
                posts.stream().map(StatusPost::getStatusId).toList(), userId)) {
            viewedStatusIds.add(v.getStatusId());
        }

        Map<String, List<StatusItem>> byUser = new LinkedHashMap<>();
        for (StatusPost post : posts) {
            byUser.computeIfAbsent(post.getUserId(), k -> new ArrayList<>())
                    .add(toItem(post, viewedStatusIds.contains(post.getStatusId())));
        }

        List<StatusFeedEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<StatusItem>> e : byUser.entrySet()) {
            boolean hasUnviewed = e.getValue().stream().anyMatch(s -> !s.viewedByMe());
            entries.add(new StatusFeedEntry(e.getKey(), e.getValue(), hasUnviewed));
        }
        entries.sort(Comparator.comparing(StatusFeedEntry::hasUnviewed).reversed());
        return entries;
    }

    /** A single user's own active statuses (for "My status") or a contact's (must actually be a contact). */
    @GetMapping("/{userId}")
    public List<StatusItem> forUser(@PathVariable String userId,
                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        if (!callerId.equals(userId) && !contactsOf(callerId).contains(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a contact");
        }
        Set<String> viewedStatusIds = new HashSet<>();
        List<StatusPost> posts = statusPostRepository.findByUserIdAndExpiresAtAfterOrderByCreatedAtAsc(userId, Instant.now());
        for (StatusView v : statusViewRepository.findByStatusIdInAndViewerId(
                posts.stream().map(StatusPost::getStatusId).toList(), callerId)) {
            viewedStatusIds.add(v.getStatusId());
        }
        return posts.stream().map(p -> toItem(p, viewedStatusIds.contains(p.getStatusId()))).toList();
    }

    @PostMapping("/{statusId}/view")
    public void view(@PathVariable String statusId,
                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        StatusPost post = statusPostRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
        if (post.getUserId().equals(callerId)) {
            return;
        }
        if (!contactsOf(callerId).contains(post.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a contact");
        }
        if (statusViewRepository.findByStatusIdAndViewerId(statusId, callerId).isEmpty()) {
            statusViewRepository.save(new StatusView(statusId, callerId, Instant.now()));
        }
    }

    public record ViewerRow(String viewerId, String viewedAt) {
    }

    /** Poster-only, same "seen by" privacy model as WhatsApp — nobody else can see who viewed a status. */
    @GetMapping("/{statusId}/viewers")
    public List<ViewerRow> viewers(@PathVariable String statusId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        StatusPost post = statusPostRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
        if (!post.getUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the poster can see viewers");
        }
        return statusViewRepository.findByStatusId(statusId).stream()
                .map(v -> new ViewerRow(v.getViewerId(), v.getViewedAt().toString()))
                .toList();
    }

    @DeleteMapping("/{statusId}")
    public void delete(@PathVariable String statusId,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        StatusPost post = statusPostRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
        if (!post.getUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the poster can delete their status");
        }
        statusViewRepository.deleteByStatusIdIn(List.of(statusId));
        statusPostRepository.deleteById(statusId);
    }

    private StatusItem toItem(StatusPost post, boolean viewedByMe) {
        return new StatusItem(post.getStatusId(), post.getUserId(), post.getMediaType(), post.getMediaObjectKey(),
                post.getTextContent(), post.getBackgroundColor(), post.getOverlayJson(), post.getCreatedAt().toString(),
                post.getExpiresAt().toString(), viewedByMe);
    }

    /** Everyone this user has an existing 1:1 message history with, or shares a group with — see the class doc comment for why this replaces a device-contacts-based audience. */
    private Set<String> contactsOf(String userId) {
        Set<String> contacts = new HashSet<>();
        for (MessageRepository.OneToOneSummaryRow row : messageRepository.findOneToOneSummariesForUser(userId)) {
            contacts.add(row.getOtherUserId());
        }
        for (var member : groupMemberRepository.findByUserId(userId)) {
            for (var other : groupMemberRepository.findByGroupId(member.getGroupId())) {
                if (!other.getUserId().equals(userId)) {
                    contacts.add(other.getUserId());
                }
            }
        }
        return contacts;
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
