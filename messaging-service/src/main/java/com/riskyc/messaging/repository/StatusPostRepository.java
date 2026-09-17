package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.StatusPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface StatusPostRepository extends JpaRepository<StatusPost, String> {

    List<StatusPost> findByUserIdInAndExpiresAtAfterOrderByCreatedAtAsc(Collection<String> userIds, Instant now);

    List<StatusPost> findByUserIdAndExpiresAtAfterOrderByCreatedAtAsc(String userId, Instant now);

    @Query("SELECT s.statusId FROM StatusPost s WHERE s.expiresAt < :now")
    List<String> findExpiredIds(@Param("now") Instant now);

    @Modifying
    @Transactional
    void deleteByStatusIdIn(Collection<String> statusIds);
}
