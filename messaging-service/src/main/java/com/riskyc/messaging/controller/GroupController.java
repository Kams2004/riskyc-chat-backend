package com.riskyc.messaging.controller;

import com.riskyc.common.dto.MessageEnvelope;
import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.entity.GroupConversation;
import com.riskyc.messaging.entity.GroupInvitation;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.entity.Message;
import com.riskyc.messaging.repository.GroupConversationRepository;
import com.riskyc.messaging.repository.GroupInvitationRepository;
import com.riskyc.messaging.repository.GroupMemberRepository;
import com.riskyc.messaging.repository.MessageRepository;
import com.riskyc.messaging.security.RevokedJtiCache;
import com.riskyc.messaging.service.PushNotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Group creation and membership. Member display names/avatars are NOT
 * duplicated here — the mobile client already resolves those via
 * auth-service's GET /api/users/{id} for every other name/avatar it shows,
 * so this only ever stores userId references.
 *
 * Nobody is ever inserted into group_member directly any more (see
 * GroupInvitation's own doc comment) — every add, whether the initial
 * member list at group-creation time or a later invite, creates a PENDING
 * GroupInvitation instead, and the invitee has to accept it themselves
 * before a real GroupMember row exists. The group's creator is the one
 * exception: they're added immediately as ADMIN, matching WhatsApp (you
 * don't invite yourself to a group you're creating).
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    /** Sentinel Message.ciphertext for a system "X joined the group" log line — see Message.isSystem's own doc comment. Mobile maps this exact string to a translated line, never displays it raw. */
    public static final String SYSTEM_MEMBER_JOINED = "__SYSTEM_GROUP_JOINED__";

    private final GroupConversationRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupInvitationRepository invitationRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;
    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public GroupController(GroupConversationRepository groupRepository, GroupMemberRepository memberRepository,
                            GroupInvitationRepository invitationRepository, MessageRepository messageRepository,
                            SimpMessagingTemplate messagingTemplate, PushNotificationService pushNotificationService,
                            JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    public record CreateGroupRequest(String name, String avatarObjectKey, List<String> memberIds) {
    }

    public record RenameGroupRequest(String name, String avatarObjectKey, Boolean onlyAdminsCanMessage) {
    }

    public record AddMembersRequest(List<String> memberIds) {
    }

    public record RoleChangeRequest(String role) {
    }

    public record MemberResult(String userId, String role) {
    }

    public record GroupResult(String id, String name, String avatarObjectKey, String createdBy,
                               boolean onlyAdminsCanMessage, List<MemberResult> members,
                               /** Invitees who haven't responded yet — lets the client show a "Pending" badge instead of pretending they're already members. */
                               List<String> pendingInviteeIds) {
    }

    public record InvitationResult(Long invitationId, String groupId, String groupName, String groupAvatarObjectKey,
                                    String inviterId, String createdAt) {
    }

    @PostMapping
    public GroupResult create(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody CreateGroupRequest request) {
        String callerId = callerIdFrom(authorization);
        String groupId = UUID.randomUUID().toString();
        GroupConversation group = new GroupConversation(groupId, request.name(), callerId, Instant.now());
        group.setAvatarObjectKey(request.avatarObjectKey());
        groupRepository.save(group);

        memberRepository.save(new GroupMember(groupId, callerId, GroupMember.Role.ADMIN, Instant.now()));
        for (String memberId : request.memberIds()) {
            if (!memberId.equals(callerId)) {
                inviteOne(groupId, callerId, memberId, group.getName());
            }
        }
        return toResult(group);
    }

    @GetMapping("/{groupId}")
    public GroupResult get(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable String groupId) {
        callerIdFrom(authorization);
        GroupConversation group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));
        return toResult(group);
    }

    /** Feeds the 1:1 contact-details page's "Groups in common" section. */
    @GetMapping("/common/{otherUserId}")
    public List<GroupResult> commonGroups(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String otherUserId) {
        String callerId = callerIdFrom(authorization);
        List<String> groupIds = memberRepository.findCommonGroupIds(callerId, otherUserId);
        return groupRepository.findAllById(groupIds).stream().map(this::toResult).toList();
    }

    /** Admin-only. Creates a PENDING invitation for each requested id that isn't already a member or already invited — never adds anyone directly. */
    @PostMapping("/{groupId}/members")
    public GroupResult addMembers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String groupId, @RequestBody AddMembersRequest request) {
        String callerId = callerIdFrom(authorization);
        GroupConversation group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));
        requireAdmin(groupId, callerId);
        for (String memberId : request.memberIds()) {
            if (memberRepository.findByGroupIdAndUserId(groupId, memberId).isEmpty()) {
                inviteOne(groupId, callerId, memberId, group.getName());
            }
        }
        return toResult(group);
    }

    private void inviteOne(String groupId, String inviterId, String inviteeId, String groupName) {
        GroupInvitation invitation = invitationRepository.findByGroupIdAndInviteeId(groupId, inviteeId)
                .orElseGet(() -> new GroupInvitation(groupId, inviterId, inviteeId, GroupInvitation.Status.PENDING, Instant.now()));
        if (invitation.getStatus() == GroupInvitation.Status.PENDING) {
            // Already invited and still waiting — re-saving would just touch
            // createdAt for no reason, so leave an outstanding invite alone.
            invitationRepository.save(invitation);
            return;
        }
        invitation.setInviterId(inviterId);
        invitation.setStatus(GroupInvitation.Status.PENDING);
        invitation.setCreatedAt(Instant.now());
        invitation.setRespondedAt(null);
        invitationRepository.save(invitation);

        messagingTemplate.convertAndSendToUser(inviteeId, "/queue/group-invitations", Map.of("groupId", groupId));
        pushNotificationService.sendToUser(inviteeId, groupName, "You've been invited to join this group", "messages-v3",
                Map.of("type", "group-invitation", "groupId", groupId));
    }

    /** My own outstanding invitations, across every group. */
    @GetMapping("/invitations")
    public List<InvitationResult> myInvitations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String callerId = callerIdFrom(authorization);
        List<GroupInvitation> invitations = invitationRepository.findByInviteeIdAndStatus(callerId, GroupInvitation.Status.PENDING);
        Map<String, GroupConversation> groupsById = new LinkedHashMap<>();
        for (GroupInvitation inv : invitations) {
            if (!groupsById.containsKey(inv.getGroupId())) {
                groupRepository.findById(inv.getGroupId()).ifPresent(g -> groupsById.put(inv.getGroupId(), g));
            }
        }
        return invitations.stream()
                .map(inv -> {
                    GroupConversation group = groupsById.get(inv.getGroupId());
                    return new InvitationResult(inv.getId(), inv.getGroupId(),
                            group != null ? group.getName() : null, group != null ? group.getAvatarObjectKey() : null,
                            inv.getInviterId(), inv.getCreatedAt().toString());
                })
                .toList();
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public GroupResult acceptInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable Long invitationId) {
        String callerId = callerIdFrom(authorization);
        GroupInvitation invitation = requireOwnPendingInvitation(invitationId, callerId);
        GroupConversation group = groupRepository.findById(invitation.getGroupId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));

        invitation.setStatus(GroupInvitation.Status.ACCEPTED);
        invitation.setRespondedAt(Instant.now());
        invitationRepository.save(invitation);

        if (memberRepository.findByGroupIdAndUserId(group.getId(), callerId).isEmpty()) {
            memberRepository.save(new GroupMember(group.getId(), callerId, GroupMember.Role.MEMBER, Instant.now()));
        }

        postSystemMessage(group.getId(), callerId, SYSTEM_MEMBER_JOINED);
        return toResult(group);
    }

    @PostMapping("/invitations/{invitationId}/decline")
    public void declineInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable Long invitationId) {
        String callerId = callerIdFrom(authorization);
        GroupInvitation invitation = requireOwnPendingInvitation(invitationId, callerId);
        invitation.setStatus(GroupInvitation.Status.DECLINED);
        invitation.setRespondedAt(Instant.now());
        invitationRepository.save(invitation);
    }

    private GroupInvitation requireOwnPendingInvitation(Long invitationId, String callerId) {
        GroupInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invitation"));
        if (!invitation.getInviteeId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invitation");
        }
        if (invitation.getStatus() != GroupInvitation.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invitation was already responded to");
        }
        return invitation;
    }

    /** Persists + broadcasts a group event log line — see Message.isSystem's own doc comment for why senderId is the person the event is about, not a fake "system" user. */
    private void postSystemMessage(String groupId, String senderId, String sentinelCiphertext) {
        String messageId = UUID.randomUUID().toString();
        Instant sentAt = Instant.now();
        Message message = new Message(messageId, groupId, senderId, groupId, sentinelCiphertext, sentAt);
        message.setGroupId(groupId);
        message.setSystem(true);
        messageRepository.save(message);

        MessageEnvelope envelope = new MessageEnvelope(messageId, groupId, senderId, groupId, sentinelCiphertext,
                sentAt, message.getStatus().name(), null, null, null, null, null, null, false, false, groupId, false,
                List.of(), null, null, null, null, false, null, null, null, true, null, null);
        messagingTemplate.convertAndSend("/topic/conversation." + groupId, envelope);
        for (GroupMember member : memberRepository.findByGroupId(groupId)) {
            if (!member.getUserId().equals(senderId)) {
                messagingTemplate.convertAndSendToUser(member.getUserId(), "/queue/messages", envelope);
            }
        }
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable String groupId, @PathVariable String userId) {
        String callerId = callerIdFrom(authorization);
        boolean selfRemoval = callerId.equals(userId);
        GroupMember.Role removedRole = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMember::getRole).orElse(null);
        if (!selfRemoval) {
            requireAdmin(groupId, callerId);
        }
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);

        // "Delete group" from a leaving admin's perspective (see plan's design
        // notes): this IS the existing self-removal/leave path, not a separate
        // destructive group-wide delete. If the member who just left was the
        // group's only admin, succeed to whichever remaining member joined
        // earliest, so the group is never left admin-less.
        if (removedRole == GroupMember.Role.ADMIN) {
            List<GroupMember> remaining = memberRepository.findByGroupIdOrderByJoinedAtAsc(groupId);
            boolean stillHasAdmin = remaining.stream().anyMatch(m -> m.getRole() == GroupMember.Role.ADMIN);
            if (!stillHasAdmin && !remaining.isEmpty()) {
                GroupMember successor = remaining.get(0);
                successor.setRole(GroupMember.Role.ADMIN);
                memberRepository.save(successor);
            }
        }
    }

    /**
     * Admin-only, supports both directions (promote and demote) — the guard
     * and setter are identical either way. Blocks an admin from demoting
     * themselves when they're the group's last admin, so a group can never
     * end up with zero admins through this endpoint (the succession logic
     * above is the only path that refills an admin-less group, and it never
     * needs to fire if this guard holds).
     */
    @PatchMapping("/{groupId}/members/{userId}/role")
    public GroupResult changeRole(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String groupId, @PathVariable String userId,
                                   @RequestBody RoleChangeRequest request) {
        String callerId = callerIdFrom(authorization);
        requireAdmin(groupId, callerId);
        GroupMember.Role newRole = GroupMember.Role.valueOf(request.role());
        GroupMember target = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this group"));
        if (newRole == GroupMember.Role.MEMBER && target.getRole() == GroupMember.Role.ADMIN) {
            long otherAdmins = memberRepository.findByGroupId(groupId).stream()
                    .filter(m -> m.getRole() == GroupMember.Role.ADMIN && !m.getUserId().equals(userId))
                    .count();
            if (otherAdmins == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The group must keep at least one admin");
            }
        }
        target.setRole(newRole);
        memberRepository.save(target);
        GroupConversation group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));
        return toResult(group);
    }

    @PatchMapping("/{groupId}")
    public GroupResult rename(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String groupId, @RequestBody RenameGroupRequest request) {
        String callerId = callerIdFrom(authorization);
        requireAdmin(groupId, callerId);
        GroupConversation group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));
        if (request.name() != null) {
            group.setName(request.name());
        }
        if (request.avatarObjectKey() != null) {
            group.setAvatarObjectKey(request.avatarObjectKey());
        }
        if (request.onlyAdminsCanMessage() != null) {
            group.setOnlyAdminsCanMessage(request.onlyAdminsCanMessage());
        }
        groupRepository.save(group);
        return toResult(group);
    }

    private void requireAdmin(String groupId, String callerId) {
        GroupMember membership = memberRepository.findByGroupIdAndUserId(groupId, callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group"));
        if (membership.getRole() != GroupMember.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a group admin can do this");
        }
    }

    private GroupResult toResult(GroupConversation group) {
        List<MemberResult> members = memberRepository.findByGroupId(group.getId()).stream()
                .map(m -> new MemberResult(m.getUserId(), m.getRole().name()))
                .toList();
        List<String> pendingInviteeIds = invitationRepository.findByGroupIdAndStatus(group.getId(), GroupInvitation.Status.PENDING)
                .stream().map(GroupInvitation::getInviteeId).toList();
        return new GroupResult(group.getId(), group.getName(), group.getAvatarObjectKey(), group.getCreatedBy(),
                group.isOnlyAdminsCanMessage(), members, pendingInviteeIds);
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
