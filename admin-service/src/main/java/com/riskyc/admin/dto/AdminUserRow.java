package com.riskyc.admin.dto;

/**
 * Local mirror of auth-service's com.riskyc.auth.dto.AdminUserRow — admin-service
 * is a separate deployable with no compile-time dependency on auth-service
 * (same "HTTP is the only inter-service contract" convention this whole
 * codebase already follows, e.g. messaging-service never imports auth-service's
 * User class either), so the shape is duplicated here to deserialize its
 * JSON response. Field names must stay in sync with the real DTO.
 */
public record AdminUserRow(String userId, String displayName, String email, String phoneNumber,
                            String createdAt, boolean suspended, String suspendedReason, String suspendedAt) {
}
