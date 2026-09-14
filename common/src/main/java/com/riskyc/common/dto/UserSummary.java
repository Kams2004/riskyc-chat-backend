package com.riskyc.common.dto;

/**
 * Minimal user projection shared across services (auth, messaging, presence)
 * so none of them need to depend on another service's persistence model.
 */
public record UserSummary(String userId, String displayName, String phoneNumber) {
}
