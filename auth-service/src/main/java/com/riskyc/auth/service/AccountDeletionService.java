package com.riskyc.auth.service;

import com.riskyc.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The exact "delete an account" sequence — hard-delete the User row, revoke
 * every one of its sessions, and best-effort unregister its push tokens —
 * extracted from UserController#deleteMe (the self-service path, which also
 * requires the caller's own session to be master) so the same sequence can
 * be reused by admin-service's admin-delete-any-account path, which has no
 * device/session to gate on at all.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = Logger.getLogger(AccountDeletionService.class.getName());

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final String internalApiKey;
    private final String messagingServiceInternalUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public AccountDeletionService(UserRepository userRepository, SessionService sessionService,
                                   @Value("${riskyc.internal.api-key:}") String internalApiKey,
                                   @Value("${riskyc.messaging-service.internal-url}") String messagingServiceInternalUrl) {
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.internalApiKey = internalApiKey;
        this.messagingServiceInternalUrl = messagingServiceInternalUrl;
    }

    public void deleteAccount(UUID userId) {
        userRepository.deleteById(userId);
        sessionService.revokeAll(userId);
        unregisterAllPushTokens(userId);
    }

    private void unregisterAllPushTokens(UUID userId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            URI uri = URI.create(messagingServiceInternalUrl + "/internal/push-tokens/" + userId);
            restTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<Void>(headers), Void.class);
        } catch (RestClientException e) {
            log.log(Level.WARNING, "Failed to unregister push tokens for deleted account " + userId, e);
        }
    }
}
