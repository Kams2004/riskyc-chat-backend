package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.DisappearingMessageSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DisappearingMessageSettingsRepository extends JpaRepository<DisappearingMessageSettings, String> {
    /** ConversationController#list scopes to just the caller's own conversation/group ids — findAll() here would load every row in the whole system on every list() call, regardless of the caller. */
    List<DisappearingMessageSettings> findByConversationIdIn(Collection<String> conversationIds);
}
