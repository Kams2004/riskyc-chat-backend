package com.riskyc.admin.service;

import com.riskyc.admin.entity.AdminAuditLog;
import com.riskyc.admin.repository.AdminAuditLogRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditLogService {

    private final AdminAuditLogRepository repository;

    public AdminAuditLogService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String adminUsername, String action, String targetType, String targetId, String details) {
        repository.save(new AdminAuditLog(adminUsername, action, targetType, targetId, details));
    }
}
