package com.riskyc.messaging.controller;

import com.riskyc.common.dto.AdminPage;
import com.riskyc.messaging.entity.StatusPost;
import com.riskyc.messaging.repository.StatusPostRepository;
import com.riskyc.messaging.repository.StatusViewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Status moderation for admin-service — a separate controller from the
 * JWT-gated, owner-only StatusController, since this needs the internal-key
 * gate and a different base path instead (same separation-of-concerns
 * precedent as SystemAccountController vs. SystemAccountInfoController).
 */
@RestController
@RequestMapping("/internal/admin/status")
public class AdminStatusController {

    private static final int TEXT_PREVIEW_LENGTH = 140;

    private final StatusPostRepository statusPostRepository;
    private final StatusViewRepository statusViewRepository;
    private final String internalApiKey;

    public AdminStatusController(StatusPostRepository statusPostRepository, StatusViewRepository statusViewRepository,
                                  @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.statusPostRepository = statusPostRepository;
        this.statusViewRepository = statusViewRepository;
        this.internalApiKey = internalApiKey;
    }

    public record StatusAdminRow(String statusId, String userId, StatusPost.MediaType mediaType,
                                  String textContentPreview, String createdAt, String expiresAt) {
    }

    @GetMapping
    public AdminPage<StatusAdminRow> list(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        requireInternalKey(apiKey);
        Page<StatusPost> result = statusPostRepository.findByExpiresAtAfterOrderByCreatedAtDesc(Instant.now(), PageRequest.of(page, size));
        List<StatusAdminRow> rows = result.getContent().stream().map(this::toRow).toList();
        return new AdminPage<>(rows, result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @DeleteMapping("/{statusId}")
    public void delete(@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey, @PathVariable String statusId) {
        requireInternalKey(apiKey);
        if (!statusPostRepository.existsById(statusId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found");
        }
        statusViewRepository.deleteByStatusIdIn(List.of(statusId));
        statusPostRepository.deleteById(statusId);
    }

    private StatusAdminRow toRow(StatusPost post) {
        String preview = post.getTextContent();
        if (preview != null && preview.length() > TEXT_PREVIEW_LENGTH) {
            preview = preview.substring(0, TEXT_PREVIEW_LENGTH) + "…";
        }
        return new StatusAdminRow(post.getStatusId(), post.getUserId(), post.getMediaType(), preview,
                post.getCreatedAt().toString(), post.getExpiresAt().toString());
    }

    private void requireInternalKey(String apiKey) {
        if (!internalApiKey.isBlank() && !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
