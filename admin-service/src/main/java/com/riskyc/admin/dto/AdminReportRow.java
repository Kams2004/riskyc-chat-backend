package com.riskyc.admin.dto;

/** Local mirror of auth-service's com.riskyc.auth.dto.AdminReportRow — see AdminUserRow's own doc comment for why this is duplicated rather than shared. */
public record AdminReportRow(String reportId, String reporterId, String reporterDisplayName,
                              String reportedId, String reportedDisplayName, String reason, String createdAt) {
}
