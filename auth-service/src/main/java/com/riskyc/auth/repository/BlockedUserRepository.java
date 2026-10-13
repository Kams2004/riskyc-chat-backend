package com.riskyc.auth.repository;

import com.riskyc.auth.dto.AdminBlockedRow;
import com.riskyc.auth.entity.BlockedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface BlockedUserRepository extends JpaRepository<BlockedUser, UUID> {
    List<BlockedUser> findByBlockerId(UUID blockerId);

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    @Modifying
    @Transactional
    void deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    /** admin-service's "blocked contacts" view — same join-in-display-names treatment as UserReportRepository#findAllWithNames. */
    @Query("""
            SELECT new com.riskyc.auth.dto.AdminBlockedRow(b.id, b.blockerId, blocker.displayName, b.blockedId, blocked.displayName, b.createdAt)
            FROM BlockedUser b
            LEFT JOIN User blocker ON blocker.id = b.blockerId
            LEFT JOIN User blocked ON blocked.id = b.blockedId
            ORDER BY b.createdAt DESC
            """)
    Page<AdminBlockedRow> findAllWithNames(Pageable pageable);
}
