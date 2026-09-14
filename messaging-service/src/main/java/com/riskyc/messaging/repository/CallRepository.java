package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CallRepository extends JpaRepository<Call, String> {
    @Query("SELECT c FROM Call c WHERE c.callerId = :userId OR c.calleeId = :userId ORDER BY c.startedAt DESC")
    List<Call> findAllForUser(@Param("userId") String userId);
}
