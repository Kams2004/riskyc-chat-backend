package com.riskyc.auth.repository;

import com.riskyc.auth.entity.BlockedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface BlockedUserRepository extends JpaRepository<BlockedUser, UUID> {
    List<BlockedUser> findByBlockerId(UUID blockerId);

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    @Modifying
    @Transactional
    void deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);
}
