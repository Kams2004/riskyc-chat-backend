package com.riskyc.admin.controller;

import com.riskyc.admin.entity.AdminAuditLog;
import com.riskyc.admin.repository.AdminAuditLogRepository;
import com.riskyc.admin.service.AdminAuthService;
import com.riskyc.common.dto.AdminPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-log")
public class AdminAuditLogController {

    private final AdminAuthService adminAuthService;
    private final AdminAuditLogRepository auditLogRepository;

    public AdminAuditLogController(AdminAuthService adminAuthService, AdminAuditLogRepository auditLogRepository) {
        this.adminAuthService = adminAuthService;
        this.auditLogRepository = auditLogRepository;
    }

    public record AuditLogRow(String id, String adminUsername, String action, String targetType, String targetId,
                               String details, String createdAt) {
    }

    @GetMapping
    public AdminPage<AuditLogRow> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        adminAuthService.callerFrom(authorization);
        Page<AdminAuditLog> result = auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        var rows = result.getContent().stream()
                .map(l -> new AuditLogRow(l.getId().toString(), l.getAdminUsername(), l.getAction(), l.getTargetType(),
                        l.getTargetId(), l.getDetails(), l.getCreatedAt().toString()))
                .toList();
        return new AdminPage<>(rows, result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }
}
