package com.riskyc.messaging.config;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket gateway. A single node holds the "simple" in-memory
 * broker below; once you run more than one messaging-service replica, swap
 * this for a Redis or RabbitMQ-backed STOMP relay so a message can reach a
 * recipient connected to a *different* node (see architecture notes on
 * fan-out).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public WebSocketConfig(JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // No SockJS: the mobile client is React Native, which has native
        // WebSocket support, so the browser-fallback transport SockJS exists
        // for isn't needed here and would only add a dependency on the client.
        // The interceptor+handler pair below let a connection carry a
        // Principal (from the ?token= JWT), which convertAndSendToUser() in
        // ChatController needs to push straight to a specific recipient.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new WebSocketAuthInterceptor(jwtIssuer, revokedJtiCache))
                .setHandshakeHandler(new UserHandshakeHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
