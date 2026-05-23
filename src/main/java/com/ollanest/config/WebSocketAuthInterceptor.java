package com.ollanest.config;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Handshake interceptor that gates WebSocket terminal access to authenticated users.
 *
 * <p>Reads the {@code olla_nest_session} cookie from the HTTP upgrade request,
 * delegates session validation to {@link AuthService#getSessionUser}, and checks
 * that the resolved user holds the {@code workspace:build} right (or is an admin).
 * Rejects the upgrade with a {@code false} return value (which translates to HTTP
 * 403) if any check fails.
 *
 * <p>This interceptor was created as part of the CRIT-1 security fix to prevent
 * unauthenticated WebSocket connections that could spawn arbitrary shell processes
 * via {@code TerminalService}.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Implemented as a {@link HandshakeInterceptor} rather than inside
 *       {@link com.ollanest.service.TerminalService} so the rejection happens
 *       before the WebSocket connection is opened, avoiding any session setup
 *       overhead for unauthorised callers.</li>
 *   <li>Admins bypass the explicit right check because admin role implies all
 *       rights.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — created during security hardening (CRIT-1 WebSocket auth)
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** Used to resolve the session cookie to a validated {@link User}. */
    private final AuthService authService;

    /**
     * Constructor-injects the authentication service.
     *
     * @param  authService  the service used to look up and validate session tokens
     * @since   v2026.1.0  — created during security hardening
     */
    public WebSocketAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Validates the WebSocket upgrade request before the handshake completes.
     *
     * <p>Steps:
     * <ol>
     *   <li>Unwraps the {@link ServletServerHttpRequest} to access cookies.</li>
     *   <li>Calls {@link AuthService#getSessionUser} to validate the session token.</li>
     *   <li>Checks that the user has the {@code workspace:build} right or is an admin.</li>
     *   <li>Returns {@code false} (and rejects the upgrade) if any check fails.</li>
     * </ol>
     *
     * @param  request    the HTTP upgrade request
     * @param  response   the HTTP upgrade response (not modified by this interceptor)
     * @param  wsHandler  the target WebSocket handler (not used here)
     * @param  attributes session attributes map (not populated by this interceptor)
     * @return            {@code true} if the handshake should proceed,
     *                    {@code false} to reject the upgrade
     * @since   v2026.1.0  — created during security hardening
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        HttpServletRequest servletReq = ((ServletServerHttpRequest) request).getServletRequest();
        User user = authService.getSessionUser(servletReq);
        if (user == null) return false;
        // Require workspace:build right; admins implicitly have all rights
        boolean hasRight = "admin".equals(user.role)
                || (user.rights != null && user.rights.contains("workspace:build"));
        return hasRight;
    }

    /**
     * No-op post-handshake callback; no cleanup is needed after a successful upgrade.
     *
     * @param  request    the HTTP upgrade request
     * @param  response   the HTTP upgrade response
     * @param  wsHandler  the target WebSocket handler
     * @param  exception  any exception thrown during the handshake, or {@code null}
     * @since   v2026.1.0  — created during security hardening
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {}
}
