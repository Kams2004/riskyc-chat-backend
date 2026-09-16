package com.riskyc.auth.repository;

import com.riskyc.auth.entity.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserReportRepository extends JpaRepository<UserReport, UUID> {
}
