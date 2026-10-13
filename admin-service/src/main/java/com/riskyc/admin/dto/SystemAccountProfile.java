package com.riskyc.admin.dto;

/** Local mirror of auth-service's SystemAccountInfoController.SystemAccountResult. */
public record SystemAccountProfile(String userId, String displayName, String avatarObjectKey,
                                    String description, String websiteUrl) {
}
