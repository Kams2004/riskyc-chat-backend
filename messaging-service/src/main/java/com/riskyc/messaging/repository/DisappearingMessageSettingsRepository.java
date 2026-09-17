package com.riskyc.messaging.repository;

import com.riskyc.messaging.entity.DisappearingMessageSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisappearingMessageSettingsRepository extends JpaRepository<DisappearingMessageSettings, String> {
}
