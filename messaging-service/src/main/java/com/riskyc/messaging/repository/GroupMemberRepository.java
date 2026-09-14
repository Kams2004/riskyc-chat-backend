package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByGroupId(String groupId);

    Optional<GroupMember> findByGroupIdAndUserId(String groupId, String userId);

    // Derived delete queries need their own transaction — a plain
    // @RestController method has none by default, which surfaced as
    // "No EntityManager with actual transaction available ... 'remove' call".
    @Modifying
    @Transactional
    void deleteByGroupIdAndUserId(String groupId, String userId);
}
