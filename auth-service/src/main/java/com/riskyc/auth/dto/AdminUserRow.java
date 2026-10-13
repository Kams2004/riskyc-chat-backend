package com.riskyc.auth.dto;

/** One row of admin-service's user list/detail view — see auth-service's AdminInternalController. */
public record AdminUserRow(String userId, String displayName, String email, String phoneNumber,
                            String createdAt, boolean suspended, String suspendedReason, String suspendedAt) {
}
