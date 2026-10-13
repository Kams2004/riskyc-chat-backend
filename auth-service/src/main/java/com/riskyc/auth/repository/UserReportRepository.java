package com.riskyc.auth.repository;

import com.riskyc.auth.dto.AdminReportRow;
import com.riskyc.auth.entity.UserReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface UserReportRepository extends JpaRepository<UserReport, UUID> {

    /** admin-service's "reported users" view — joins in both parties' display names so the UI never has to resolve them separately. Fully parameterized (Pageable only), no injection surface. */
    @Query("""
            SELECT new com.riskyc.auth.dto.AdminReportRow(r.id, r.reporterId, reporter.displayName, r.reportedId, reported.displayName, r.reason, r.createdAt)
            FROM UserReport r
            LEFT JOIN User reporter ON reporter.id = r.reporterId
            LEFT JOIN User reported ON reported.id = r.reportedId
            ORDER BY r.createdAt DESC
            """)
    Page<AdminReportRow> findAllWithNames(Pageable pageable);
}
