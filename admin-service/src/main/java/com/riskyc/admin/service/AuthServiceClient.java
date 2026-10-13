package com.riskyc.admin.service;

import com.riskyc.admin.dto.AdminBlockedRow;
import com.riskyc.admin.dto.AdminReportRow;
import com.riskyc.admin.dto.AdminUserRow;
import com.riskyc.admin.dto.SystemAccountProfile;
import com.riskyc.common.dto.AdminPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/** admin-service's only way to reach auth-service's data — every call hits one of auth-service's internal/** endpoints, guarded by the same shared X-Internal-Api-Key every other inter-service call in this codebase already uses. */
@Service
public class AuthServiceClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final String internalApiKey;

    public AuthServiceClient(@Value("${riskyc.auth-service.internal-url}") String baseUrl,
                              @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.baseUrl = baseUrl;
        this.internalApiKey = internalApiKey;
    }

    public AdminPage<AdminUserRow> listUsers(String q, int page, int size) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/internal/admin/users")
                .queryParam("q", q).queryParam("page", page).queryParam("size", size).build().toUri();
        return exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<AdminPage<AdminUserRow>>() {
        });
    }

    public record UserCountSummary(long totalUsers, long suspendedUsers) {
    }

    public UserCountSummary userCount() {
        return exchange(URI.create(baseUrl + "/internal/admin/users/count"), HttpMethod.GET, null,
                new ParameterizedTypeReference<UserCountSummary>() {
                });
    }

    public AdminUserRow getUser(String userId) {
        return exchange(URI.create(baseUrl + "/internal/admin/users/" + userId), HttpMethod.GET, null,
                new ParameterizedTypeReference<AdminUserRow>() {
                });
    }

    public AdminUserRow suspend(String userId, String reason) {
        return exchange(URI.create(baseUrl + "/internal/admin/users/" + userId + "/suspend"), HttpMethod.POST,
                Map.of("reason", reason == null ? "" : reason), new ParameterizedTypeReference<AdminUserRow>() {
                });
    }

    public AdminUserRow unsuspend(String userId) {
        return exchange(URI.create(baseUrl + "/internal/admin/users/" + userId + "/unsuspend"), HttpMethod.POST, null,
                new ParameterizedTypeReference<AdminUserRow>() {
                });
    }

    public void deleteUser(String userId) {
        exchange(URI.create(baseUrl + "/internal/admin/users/" + userId), HttpMethod.DELETE, null,
                new ParameterizedTypeReference<Void>() {
                });
    }

    public AdminPage<AdminReportRow> listReports(int page, int size) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/internal/admin/reports")
                .queryParam("page", page).queryParam("size", size).build().toUri();
        return exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<AdminPage<AdminReportRow>>() {
        });
    }

    public AdminPage<AdminBlockedRow> listBlocked(int page, int size) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/internal/admin/blocked")
                .queryParam("page", page).queryParam("size", size).build().toUri();
        return exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<AdminPage<AdminBlockedRow>>() {
        });
    }

    public SystemAccountProfile getSystemAccountProfile() {
        return exchange(URI.create(baseUrl + "/internal/system-account/profile"), HttpMethod.GET, null,
                new ParameterizedTypeReference<SystemAccountProfile>() {
                });
    }

    public SystemAccountProfile updateSystemAccountProfile(String displayName, String avatarObjectKey, String description, String websiteUrl) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("avatarObjectKey", avatarObjectKey);
        body.put("description", description);
        body.put("websiteUrl", websiteUrl);
        return exchange(URI.create(baseUrl + "/internal/admin/system-account"), HttpMethod.PUT, body,
                new ParameterizedTypeReference<SystemAccountProfile>() {
                });
    }

    private <T> T exchange(URI uri, HttpMethod method, Object body, ParameterizedTypeReference<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (!internalApiKey.isBlank()) {
            headers.set("X-Internal-Api-Key", internalApiKey);
        }
        ResponseEntity<T> response = restTemplate.exchange(uri, method, new HttpEntity<>(body, headers), responseType);
        return response.getBody();
    }
}
