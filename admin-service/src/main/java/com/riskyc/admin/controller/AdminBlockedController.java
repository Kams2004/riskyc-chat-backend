package com.riskyc.admin.controller;

import com.riskyc.admin.dto.AdminBlockedRow;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.admin.service.AuthServiceClient;
import com.riskyc.common.dto.AdminPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/blocked")
public class AdminBlockedController {

    private final AdminAuthService adminAuthService;
    private final AuthServiceClient authServiceClient;

    public AdminBlockedController(AdminAuthService adminAuthService, AuthServiceClient authServiceClient) {
        this.adminAuthService = adminAuthService;
        this.authServiceClient = authServiceClient;
    }

    @GetMapping
    public AdminPage<AdminBlockedRow> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        adminAuthService.callerFrom(authorization);
        return authServiceClient.listBlocked(page, size);
    }
}
