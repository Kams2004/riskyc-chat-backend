package com.riskyc.auth.service;

import com.riskyc.auth.entity.User;
import com.riskyc.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The RiskyC Fashion "official" account — same idea as WhatsApp's built-in
 * system account, but implemented as a perfectly ordinary User row rather
 * than special-cased chat logic. What makes it special is entirely
 * access-related: AuthController's /otp/request recognizes accessEmail and
 * logs straight into this account instead of running the normal OTP flow
 * (see that controller for the full mechanism), and every brand-new user
 * gets one welcome message from it, sent here via messaging-service's
 * internal API — the exact same 1:1 conversationId scheme the clients use
 * (conversationIdFor: the two user ids, sorted, joined with '_') so it
 * lands in the right thread on first load, no different from any other
 * message.
 */
@Service
public class SystemAccountService {

    private static final Logger log = Logger.getLogger(SystemAccountService.class.getName());

    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    private final String accessEmail;
    private final String displayName;
    private final String welcomeMessage;
    private final String description;
    private final String websiteUrl;
    private final String messagingServiceInternalUrl;
    private final String internalApiKey;

    public SystemAccountService(UserRepository userRepository,
                                 @Value("${riskyc.system-account.access-email:}") String accessEmail,
                                 @Value("${riskyc.system-account.display-name:RiskyC Fashion}") String displayName,
                                 @Value("${riskyc.system-account.welcome-message:}") String welcomeMessage,
                                 @Value("${riskyc.system-account.description:}") String description,
                                 @Value("${riskyc.system-account.website-url:}") String websiteUrl,
                                 @Value("${riskyc.messaging-service.internal-url}") String messagingServiceInternalUrl,
                                 @Value("${riskyc.internal.api-key:}") String internalApiKey) {
        this.userRepository = userRepository;
        this.accessEmail = accessEmail;
        this.displayName = displayName;
        this.welcomeMessage = welcomeMessage;
        this.description = description;
        this.websiteUrl = websiteUrl;
        this.messagingServiceInternalUrl = messagingServiceInternalUrl;
        this.internalApiKey = internalApiKey;
    }

    public boolean isEnabled() {
        return !accessEmail.isBlank();
    }

    public String getDescription() {
        return description;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public boolean matchesAccessIdentifier(String email) {
        return isEnabled() && email != null && accessEmail.equals(email);
    }

    /** Idempotent find-or-create, same pattern as AuthController's normal new-user path. */
    public User findOrCreateAccount() {
        return userRepository.findByEmail(accessEmail)
                .orElseGet(() -> userRepository.save(User.withEmail(accessEmail, displayName)));
    }

    /**
     * Best-effort and fire-and-forget on failure — messaging-service being
     * briefly unreachable at signup time shouldn't fail account creation
     * itself, the same tradeoff RevokedJtiCache makes for the reverse
     * direction. Skips entirely if the feature is disabled (blank
     * accessEmail) so there's no system account to send from.
     */
    public void sendWelcomeMessage(User newUser) {
        if (!isEnabled() || newUser.getEmail() != null && accessEmail.equals(newUser.getEmail())) {
            return;
        }
        try {
            User system = findOrCreateAccount();
            String conversationId = List.of(system.getId().toString(), newUser.getId().toString()).stream()
                    .sorted().reduce((a, b) -> a + "_" + b).orElseThrow();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("conversationId", conversationId);
            body.put("senderId", system.getId().toString());
            body.put("senderDisplayName", system.getDisplayName());
            body.put("senderAvatarObjectKey", system.getAvatarObjectKey());
            body.put("recipientId", newUser.getId().toString());
            body.put("text", welcomeMessage);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (!internalApiKey.isBlank()) {
                headers.set("X-Internal-Api-Key", internalApiKey);
            }
            URI uri = URI.create(messagingServiceInternalUrl + "/internal/system-account/welcome");
            restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
        } catch (RestClientException e) {
            log.log(Level.WARNING, "Failed to send system-account welcome message to new user " + newUser.getId(), e);
        }
    }
}
