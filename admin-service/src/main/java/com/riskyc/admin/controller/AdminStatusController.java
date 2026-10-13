package com.riskyc.admin.controller;

import com.riskyc.admin.dto.StatusAdminRow;
import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.service.AdminAuditLogService;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.MessagingServiceClient;
import com.riskyc.common.dto.AdminPage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/status")
public class AdminStatusController {

    private final AdminAuthService adminAuthService;
    private final MessagingServiceClient messagingServiceClient;
    private final AdminAuditLogService auditLog;

    public AdminStatusController(AdminAuthService adminAuthService, MessagingServiceClient messagingServiceClient, AdminAuditLogService auditLog) {
        this.adminAuthService = adminAuthService;
        this.messagingServiceClient = messagingServiceClient;
        this.auditLog = auditLog;
    }

    @GetMapping
    public AdminPage<StatusAdminRow> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        adminAuthService.callerFrom(authorization);
        return messagingServiceClient.listStatuses(page, size);
    }

    @DeleteMapping("/{statusId}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String statusId) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        messagingServiceClient.deleteStatus(statusId);
        auditLog.record(caller.getUsername(), "DELETE_STATUS", "STATUS", statusId, null);
    }
}
