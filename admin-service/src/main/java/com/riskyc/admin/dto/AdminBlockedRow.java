package com.riskyc.admin.dto;

/** Local mirror of auth-service's com.riskyc.auth.dto.AdminBlockedRow — see AdminUserRow's own doc comment for why this is duplicated rather than shared. */
public record AdminBlockedRow(String id, String blockerId, String blockerDisplayName,
                               String blockedId, String blockedDisplayName, String createdAt) {
}
