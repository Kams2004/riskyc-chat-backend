package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.GroupInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, Long> {

    Optional<GroupInvitation> findByGroupIdAndInviteeId(String groupId, String inviteeId);

    /** Feeds the "New group" / "Add members" pickers — excludes anyone with an outstanding PENDING invite from being invited twice. */
    List<GroupInvitation> findByGroupIdAndStatus(String groupId, GroupInvitation.Status status);

    /** My own pending invitations, across every group — see GroupController#myInvitations. */
    List<GroupInvitation> findByInviteeIdAndStatus(String inviteeId, GroupInvitation.Status status);
}
