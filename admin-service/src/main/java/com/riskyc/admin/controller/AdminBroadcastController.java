package com.riskyc.admin.controller;

import com.riskyc.admin.entity.AdminAccount;
import com.riskyc.admin.service.AdminAuditLogService;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.MessagingServiceClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sends a message via the official system account — to one user, or everyone. Text-only for now (see MessagingServiceClient's own doc comment on the scope line). */
@RestController
@RequestMapping("/api/admin/broadcast")
public class AdminBroadcastController {

    private final AdminAuthService adminAuthService;
    private final MessagingServiceClient messagingServiceClient;
    private final AdminAuditLogService auditLog;

    public AdminBroadcastController(AdminAuthService adminAuthService, MessagingServiceClient messagingServiceClient,
                                     AdminAuditLogService auditLog) {
        this.adminAuthService = adminAuthService;
        this.messagingServiceClient = messagingServiceClient;
        this.auditLog = auditLog;
    }

    public record BroadcastRequest(String text, String recipientId) {
    }

    @PostMapping
    public MessagingServiceClient.BroadcastResult send(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestBody BroadcastRequest request) {
        AdminAccount caller = adminAuthService.callerFrom(authorization);
        MessagingServiceClient.BroadcastResult result = messagingServiceClient.broadcast(request.text(), request.recipientId());
        auditLog.record(caller.getUsername(), "BROADCAST", "USER",
                request.recipientId() != null ? request.recipientId() : "ALL",
                "Sent to " + result.recipientCount() + " recipient(s): " + request.text());
        return result;
    }
}
