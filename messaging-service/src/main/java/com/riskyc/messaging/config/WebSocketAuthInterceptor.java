package com.riskyc.messaging.config;

import com.riskyc.common.security.JwtIssuer;
import com.riskyc.messaging.security.RevokedJtiCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Reads the JWT the mobile client puts on the handshake URL (`/ws?token=...`
 * — WebSocket has no way to attach a custom Authorization header) and stashes
 * the verified userId in the session attributes, where {@link UserHandshakeHandler}
 * picks it up to build a Principal. A missing or invalid token doesn't reject
 * the handshake — it just leaves the session anonymous, so per-conversation
 * topics (which need no Principal) keep working exactly as before; only the
 * per-user push in ChatController#send degrades silently for that session.
 */
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtIssuer jwtIssuer;
    private final RevokedJtiCache revokedJtiCache;

    public WebSocketAuthInterceptor(JwtIssuer jwtIssuer, RevokedJtiCache revokedJtiCache) {
        this.jwtIssuer = jwtIssuer;
        this.revokedJtiCache = revokedJtiCache;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        if (token != null) {
            try {
                JwtIssuer.JwtClaims claims = jwtIssuer.verifyAndGetClaims(token);
                // The most consequential of all the revocation checks in this
                // service — this gates the live socket a signed-out device
                // would otherwise keep using for messages/calls indefinitely
                // (up to the JWT's 30-day natural expiry) even after "sign
                // out this device" was used elsewhere.
                if (!revokedJtiCache.isRevoked(claims.jti())) {
                    attributes.put("userId", claims.subject());
                } else {
                    log.debug("Rejected revoked WS session token, continuing handshake anonymously");
                }
            } catch (RuntimeException e) {
                log.debug("Rejected invalid WS token, continuing handshake anonymously", e);
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
