package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PushTokenRepository extends JpaRepository<PushToken, String> {
    List<PushToken> findByUserId(String userId);
}
