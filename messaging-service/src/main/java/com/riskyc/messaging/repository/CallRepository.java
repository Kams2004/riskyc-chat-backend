package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CallRepository extends JpaRepository<Call, String> {
    @Query("SELECT c FROM Call c WHERE c.callerId = :userId OR c.calleeId = :userId ORDER BY c.startedAt DESC")
    List<Call> findAllForUser(@Param("userId") String userId);

    /** Feeds CallTimeoutJob — a call nobody ever answered, declined, or explicitly hung up. */
    @Query("SELECT c FROM Call c WHERE c.status = com.riskyc.messaging.entity.Call.CallStatus.RINGING AND c.startedAt < :cutoff")
    List<Call> findStaleRinging(@Param("cutoff") Instant cutoff);
}
