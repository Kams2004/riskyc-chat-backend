package com.riskyc.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of admin-service's "reported users" view — a UserReport joined with both parties' display names. */
public record AdminReportRow(UUID reportId, UUID reporterId, String reporterDisplayName,
                              UUID reportedId, String reportedDisplayName, String reason, Instant createdAt) {
}
