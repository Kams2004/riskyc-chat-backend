package com.riskyc.auth.controller;

import com.riskyc.auth.dto.AdminBlockedRow;
import com.riskyc.auth.dto.AdminReportRow;
import com.riskyc.auth.dto.AdminUserRow;
import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.BlockedUserRepository;
import com.riskyc.auth.repository.UserReportRepository;
import com.riskyc.auth.repository.UserRepository;
import com.riskyc.auth.service.AccountDeletionService;
import com.riskyc.auth.service.SessionService;
import com.riskyc.common.dto.AdminPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * admin-service's entire view into this service's data — user list/search,
 * suspend/unsuspend, admin-delete, and the "reported users"/"blocked
 * contacts" moderation views. Shared-secret protected exactly like every
 * other /internal/** endpoint in this codebase (see SessionController's own
 * doc comment on the pattern) — admin-service is the only caller, never
 * reachable from a browser (not CORS-mapped).
 */
@RestController
public class AdminInternalController {

    private final UserRepository userRepository;
    private final UserReportRepository userReportRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final SessionService sessionService;
    private final AccountDeletionService accountDeletionService;
    private final String internalApiKey;

    public AdminInternalController(UserRepository userRepository, UserReportRepository userReportRepository,
                                    BlockedUserRepository blockedUserRepository, SessionService sessionService,
                                    AccountDeletionService accountDeletionService,
                                    @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.userRepository = userRepository;
        this.userReportRepository = userReportRepository;
        this.blockedUserRepository = blockedUserRepository;
        this.sessionService = sessionService;
        this.accountDeletionService = accountDeletionService;
        this.internalApiKey = internalApiKey;
    }

    public record SuspendRequest(String reason) {
    }

    public record UserCountResult(long totalUsers, long suspendedUsers) {
    }

    @GetMapping("/internal/admin/users")
    public AdminPage<AdminUserRow> listUsers(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                              @RequestParam(defaultValue = "") String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "25") int size) {
        requireInternalKey(apiKey);
        Page<User> result = userRepository.adminSearch(q.trim(), PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return new AdminPage<>(result.getContent().stream().map(this::toRow).toList(),
                result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @GetMapping("/internal/admin/users/count")
    public UserCountResult userCount(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        requireInternalKey(apiKey);
        return new UserCountResult(userRepository.count(), userRepository.countBySuspendedTrue());
    }

    @GetMapping("/internal/admin/users/{userId}")
    public AdminUserRow getUser(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey, @PathVariable UUID userId) {
        requireInternalKey(apiKey);
        return toRow(userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user")));
    }

    /** Also revokes every linked device's session immediately — a suspended account shouldn't keep working on devices it was already signed into. */
    @PostMapping("/internal/admin/users/{userId}/suspend")
    public AdminUserRow suspend(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                 @PathVariable UUID userId, @RequestBody SuspendRequest request) {
        requireInternalKey(apiKey);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        user.suspend(request.reason(), Instant.now());
        userRepository.save(user);
        sessionService.revokeAll(userId);
        return toRow(user);
    }

    @PostMapping("/internal/admin/users/{userId}/unsuspend")
    public AdminUserRow unsuspend(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey, @PathVariable UUID userId) {
        requireInternalKey(apiKey);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        user.unsuspend();
        userRepository.save(user);
        return toRow(user);
    }

    @DeleteMapping("/internal/admin/users/{userId}")
    public void deleteUser(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey, @PathVariable UUID userId) {
        requireInternalKey(apiKey);
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
        }
        accountDeletionService.deleteAccount(userId);
    }

    @GetMapping("/internal/admin/reports")
    public AdminPage<AdminReportRow> listReports(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "25") int size) {
        requireInternalKey(apiKey);
        Page<AdminReportRow> result = userReportRepository.findAllWithNames(PageRequest.of(page, size));
        return new AdminPage<>(result.getContent(), result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @GetMapping("/internal/admin/blocked")
    public AdminPage<AdminBlockedRow> listBlocked(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size) {
        requireInternalKey(apiKey);
        Page<AdminBlockedRow> result = blockedUserRepository.findAllWithNames(PageRequest.of(page, size));
        return new AdminPage<>(result.getContent(), result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    private AdminUserRow toRow(User u) {
        return new AdminUserRow(u.getId().toString(), u.getDisplayName(), u.getEmail(), u.getPhoneNumber(),
                u.getCreatedAt().toString(), u.isSuspended(), u.getSuspendedReason(),
                u.getSuspendedAt() == null ? null : u.getSuspendedAt().toString());
    }

    private void requireInternalKey(String apiKey) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
