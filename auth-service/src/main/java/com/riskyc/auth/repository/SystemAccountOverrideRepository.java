package com.riskyc.auth.repository;

import com.riskyc.auth.entity.SystemAccountOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SystemAccountOverrideRepository extends JpaRepository<SystemAccountOverride, UUID> {
}
