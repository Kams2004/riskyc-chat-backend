package com.riskyc.admin.controller;

import com.riskyc.admin.dto.SystemAccountProfile;
import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.service.AdminAuditLogService;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.AuthServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/system-account")
public class AdminSystemAccountController {

    private final AdminAuthService adminAuthService;
    private final AuthServiceClient authServiceClient;
    private final AdminAuditLogService auditLog;

    public AdminSystemAccountController(AdminAuthService adminAuthService, AuthServiceClient authServiceClient, AdminAuditLogService auditLog) {
        this.adminAuthService = adminAuthService;
        this.authServiceClient = authServiceClient;
        this.auditLog = auditLog;
    }

    public record UpdateRequest(String displayName, String avatarObjectKey, String description, String websiteUrl) {
    }

    @GetMapping
    public SystemAccountProfile get(@RequestHeader(value = "Authorization", required = false) String authorization) {
        adminAuthService.callerFrom(authorization);
        try {
            return authServiceClient.getSystemAccountProfile();
        } catch (HttpClientErrorException.NotFound e) {
            // auth-service returns 404 when RISKYC_SYSTEM_ACCOUNT_ACCESS_EMAIL isn't
            // configured — surface that as a clean 404 instead of letting it fall
            // through as an unexplained 500.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No official account configured");
        }
    }

    @PutMapping
    public SystemAccountProfile update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody UpdateRequest request) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        SystemAccountProfile result = authServiceClient.updateSystemAccountProfile(
                request.displayName(), request.avatarObjectKey(), request.description(), request.websiteUrl());
        auditLog.record(caller.getUsername(), "UPDATE_SYSTEM_ACCOUNT", "SYSTEM_ACCOUNT", result.userId(), null);
        return result;
    }
}
