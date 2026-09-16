package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByGroupId(String groupId);

    /** Feeds last-admin-leaves succession: promote the earliest-joined remaining member. */
    List<GroupMember> findByGroupIdOrderByJoinedAtAsc(String groupId);

    List<GroupMember> findByUserId(String userId);

    Optional<GroupMember> findByGroupIdAndUserId(String groupId, String userId);

    /** Feeds the contact-details "Groups in common" section. */
    @Query("SELECT gm1.groupId FROM GroupMember gm1 WHERE gm1.userId = :userId " +
            "AND gm1.groupId IN (SELECT gm2.groupId FROM GroupMember gm2 WHERE gm2.userId = :otherUserId)")
    List<String> findCommonGroupIds(@Param("userId") String userId, @Param("otherUserId") String otherUserId);

    // Derived delete queries need their own transaction — a plain
    // @RestController method has none by default, which surfaced as
    // "No EntityManager with actual transaction available ... 'remove' call".
    @Modifying
    @Transactional
    void deleteByGroupIdAndUserId(String groupId, String userId);
}
