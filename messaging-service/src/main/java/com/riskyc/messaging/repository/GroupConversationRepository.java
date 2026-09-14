package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.GroupConversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupConversationRepository extends JpaRepository<GroupConversation, String> {
}
