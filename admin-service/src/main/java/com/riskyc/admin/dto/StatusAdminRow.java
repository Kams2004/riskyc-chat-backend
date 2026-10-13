package com.riskyc.admin.dto;

/** Local mirror of messaging-service's AdminStatusController.StatusAdminRow. */
public record StatusAdminRow(String statusId, String userId, String mediaType, String textContentPreview,
                              String createdAt, String expiresAt) {
}
