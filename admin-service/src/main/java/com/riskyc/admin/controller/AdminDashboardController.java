package com.riskyc.admin.controller;

import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.AuthServiceClient;
import com.riskyc.admin.service.PresenceServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminAuthService adminAuthService;
    private final AuthServiceClient authServiceClient;
    private final PresenceServiceClient presenceServiceClient;

    public AdminDashboardController(AdminAuthService adminAuthService, AuthServiceClient authServiceClient,
                                     PresenceServiceClient presenceServiceClient) {
        this.adminAuthService = adminAuthService;
        this.authServiceClient = authServiceClient;
        this.presenceServiceClient = presenceServiceClient;
    }

    public record DashboardSummary(long totalUsers, long suspendedUsers, long onlineUsers, long totalReports, long totalBlockedPairs) {
    }

    @GetMapping("/summary")
    public DashboardSummary summary(@RequestHeader(value = "Authorization", required = false) String authorization) {
        adminAuthService.callerFrom(authorization);
        AuthServiceClient.UserCountSummary userCounts = authServiceClient.userCount();
        long onlineUsers = presenceServiceClient.onlineCount();
        long totalReports = authServiceClient.listReports(0, 1).totalElements();
        long totalBlockedPairs = authServiceClient.listBlocked(0, 1).totalElements();
        return new DashboardSummary(userCounts.totalUsers(), userCounts.suspendedUsers(), onlineUsers, totalReports, totalBlockedPairs);
    }
}
