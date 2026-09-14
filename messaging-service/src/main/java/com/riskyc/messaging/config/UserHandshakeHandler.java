package com.riskyc.messaging.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Turns the userId that {@link WebSocketAuthInterceptor} put in the session
 * attributes into a Principal, which is what convertAndSendToUser() needs to
 * route a message to this specific user's session(s) rather than a shared topic.
 */
public class UserHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
        Object userId = attributes.get("userId");
        if (userId == null) {
            return super.determineUser(request, wsHandler, attributes);
        }
        String name = userId.toString();
        return () -> name;
    }
}
