package com.riskyc.admin.controller;

import com.riskyc.admin.dto.AdminUserRow;
import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.service.AdminAuditLogService;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.AuthServiceClient;
import com.riskyc.common.dto.AdminPage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {

    private final AdminAuthService adminAuthService;
    private final AuthServiceClient authServiceClient;
    private final AdminAuditLogService auditLog;

    public AdminUsersController(AdminAuthService adminAuthService, AuthServiceClient authServiceClient, AdminAuditLogService auditLog) {
        this.adminAuthService = adminAuthService;
        this.authServiceClient = authServiceClient;
        this.auditLog = auditLog;
    }

    public record SuspendRequest(String reason) {
    }

    @GetMapping
    public AdminPage<AdminUserRow> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        adminAuthService.callerFrom(authorization);
        return authServiceClient.listUsers(q, page, size);
    }

    @GetMapping("/{userId}")
    public AdminUserRow get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String userId) {
        adminAuthService.callerFrom(authorization);
        return authServiceClient.getUser(userId);
    }

    @PostMapping("/{userId}/suspend")
    public AdminUserRow suspend(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable String userId, @RequestBody SuspendRequest request) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        AdminUserRow result = authServiceClient.suspend(userId, request.reason());
        auditLog.record(caller.getUsername(), "SUSPEND_USER", "USER", userId, request.reason());
        return result;
    }

    @PostMapping("/{userId}/unsuspend")
    public AdminUserRow unsuspend(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String userId) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        AdminUserRow result = authServiceClient.unsuspend(userId);
        auditLog.record(caller.getUsername(), "UNSUSPEND_USER", "USER", userId, null);
        return result;
    }

    @DeleteMapping("/{userId}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String userId) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        authServiceClient.deleteUser(userId);
        auditLog.record(caller.getUsername(), "DELETE_USER", "USER", userId, null);
    }
}
