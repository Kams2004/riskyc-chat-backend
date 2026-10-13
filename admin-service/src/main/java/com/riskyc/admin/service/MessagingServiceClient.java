package com.riskyc.admin.service;

import com.riskyc.admin.dto.StatusAdminRow;
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

/** admin-service's only way to reach messaging-service's data — broadcast + status moderation, same shared internal-key pattern as AuthServiceClient. */
@Service
public class MessagingServiceClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final String internalApiKey;

    public MessagingServiceClient(@Value("${riskyc.messaging-service.internal-url}") String baseUrl,
                                   @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.baseUrl = baseUrl;
        this.internalApiKey = internalApiKey;
    }

    public record BroadcastResult(int recipientCount) {
    }

    public BroadcastResult broadcast(String text, String recipientId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("text", text);
        body.put("recipientId", recipientId);
        return exchange(URI.create(baseUrl + "/internal/admin/broadcast"), HttpMethod.POST, body,
                new ParameterizedTypeReference<BroadcastResult>() {
                });
    }

    public AdminPage<StatusAdminRow> listStatuses(int page, int size) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/internal/admin/status")
                .queryParam("page", page).queryParam("size", size).build().toUri();
        return exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<AdminPage<StatusAdminRow>>() {
        });
    }

    public void deleteStatus(String statusId) {
        exchange(URI.create(baseUrl + "/internal/admin/status/" + statusId), HttpMethod.DELETE, null,
                new ParameterizedTypeReference<Void>() {
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
