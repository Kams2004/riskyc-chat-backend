package com.riskyc.messaging.controller;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.entity.GroupConversation;
import com.riskyc.messaging.entity.GroupMember;
import com.riskyc.messaging.repository.GroupConversationRepository;
import com.riskyc.messaging.repository.GroupMemberRepository;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.UUID;

/**
 * Group creation and membership. Member display names/avatars are NOT
 * duplicated here — the mobile client already resolves those via
 * auth-service's GET /api/users/{id} for every other name/avatar it shows,
 * so this only ever stores userId references.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupConversationRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final JwtIssuer jwtIssuer;

    public GroupController(GroupConversationRepository groupRepository, GroupMemberRepository memberRepository, JwtIssuer jwtIssuer) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.jwtIssuer = jwtIssuer;
    }

    public record CreateGroupRequest(String name, String avatarObjectKey, List<String> memberIds) {
    }

    public record RenameGroupRequest(String name, String avatarObjectKey) {
    }

    public record AddMembersRequest(List<String> memberIds) {
    }

    public record MemberResult(String userId, String role) {
    }

    public record GroupResult(String id, String name, String avatarObjectKey, String createdBy, List<MemberResult> members) {
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
                memberRepository.save(new GroupMember(groupId, memberId, GroupMember.Role.MEMBER, Instant.now()));
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

    @PostMapping("/{groupId}/members")
    public GroupResult addMembers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String groupId, @RequestBody AddMembersRequest request) {
        String callerId = callerIdFrom(authorization);
        GroupConversation group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group"));
        requireAdmin(groupId, callerId);
        for (String memberId : request.memberIds()) {
            if (memberRepository.findByGroupIdAndUserId(groupId, memberId).isEmpty()) {
                memberRepository.save(new GroupMember(groupId, memberId, GroupMember.Role.MEMBER, Instant.now()));
            }
        }
        return toResult(group);
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable String groupId, @PathVariable String userId) {
        String callerId = callerIdFrom(authorization);
        if (!callerId.equals(userId)) {
            requireAdmin(groupId, callerId);
        }
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
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
        return new GroupResult(group.getId(), group.getName(), group.getAvatarObjectKey(), group.getCreatedBy(), members);
    }

    private String callerIdFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        try {
            return jwtIssuer.verifyAndGetSubject(authorization.substring("Bearer ".length()));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
