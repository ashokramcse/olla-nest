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
 * WebSocket handshake interceptor that gates terminal access to authenticated and
 * authorised users before a connection is established.
 *
 * <h3>Why this class exists</h3>
 * <p>Created as part of the CRIT-1 security fix. Without this interceptor, any HTTP
 * client (including unauthenticated ones) could upgrade to a WebSocket connection on
 * {@code /api/terminal} and spawn an arbitrary shell process via
 * {@link com.ollanest.service.TerminalService}. By rejecting the upgrade at
 * handshake time, no session or process setup overhead is incurred for unauthorised
 * callers.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Implemented as a {@link HandshakeInterceptor} rather than inside
 *       {@link com.ollanest.service.TerminalService} to keep the service focused on
 *       I/O bridging and to ensure the rejection happens before any WebSocket
 *       infrastructure is initialised.</li>
 *   <li>Admins bypass the explicit {@code workspace:build} right check because the
 *       admin role implies all rights.</li>
 *   <li>The interceptor only inspects the upgrade request; it does not modify the
 *       response or populate the attributes map.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — created during security hardening (CRIT-1 WebSocket
 *       authentication)</li>
 *   <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** Used to resolve the session cookie to a validated {@link User}. */
    private final AuthService authService;

    /**
     * Constructor-injects the authentication service.
     *
     * @param  authService  the service used to look up and validate session tokens
     * @since  v2026.1.0
     */
    public WebSocketAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Validates the WebSocket upgrade request before the handshake completes.
     *
     * <p>Steps performed:
     * <ol>
     *   <li>Unwraps the {@link ServletServerHttpRequest} to access the underlying
     *       servlet request (needed to read cookies).</li>
     *   <li>Calls {@link AuthService#getSessionUser} to resolve and validate the
     *       session cookie.</li>
     *   <li>Checks that the resolved user has the {@code workspace:build} right or
     *       holds the {@code admin} role.</li>
     *   <li>Returns {@code false} (which produces an HTTP 403 response) if any
     *       check fails.</li>
     * </ol>
     *
     * @param  request    the HTTP upgrade request
     * @param  response   the HTTP upgrade response (not modified by this interceptor)
     * @param  wsHandler  the target WebSocket handler (not used in this interceptor)
     * @param  attributes the session attributes map (not populated by this interceptor)
     * @return            {@code true} if the handshake should proceed;
     *                    {@code false} to reject the upgrade with HTTP 403
     * @since  v2026.1.0
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
     * No-op post-handshake callback; no cleanup is required after a successful
     * WebSocket upgrade.
     *
     * @param  request    the HTTP upgrade request
     * @param  response   the HTTP upgrade response
     * @param  wsHandler  the target WebSocket handler
     * @param  exception  any exception thrown during the handshake, or {@code null}
     * @since  v2026.1.0
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {}
}
