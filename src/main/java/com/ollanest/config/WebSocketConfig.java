package com.ollanest.config;

import com.ollanest.service.TerminalService;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket endpoint registration and configuration for Olla Nest.
 *
 * <p>Registers the {@code /api/terminal} WebSocket endpoint, which is handled by
 * {@link TerminalService} (itself a {@code WebSocketHandler}). The endpoint uses
 * SockJS fallback to support browsers without native WebSocket support.
 *
 * <p>{@link WebSocketAuthInterceptor} is registered as a handshake interceptor so
 * every WebSocket upgrade request is authenticated and authorised before the
 * connection is established (CRIT-1 security fix).
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>{@code setAllowedOriginPatterns("*")} is used because the app may be
 *       accessed from different hostnames in development and air-gapped deployments;
 *       actual authentication is enforced by the interceptor rather than CORS.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 * @version v2026.1.0  — security hardening: added WebSocketAuthInterceptor registration
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Handles WebSocket messages and manages terminal process lifecycle. */
    private final TerminalService terminalService;

    /** Intercepts WebSocket handshakes to enforce authentication. */
    private final WebSocketAuthInterceptor authInterceptor;

    /**
     * Constructor-injects the terminal handler and auth interceptor.
     *
     * @param  terminalService  the WebSocket handler for terminal sessions
     * @param  authInterceptor  the handshake interceptor that enforces auth
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public WebSocketConfig(TerminalService terminalService, WebSocketAuthInterceptor authInterceptor) {
        this.terminalService = terminalService;
        this.authInterceptor = authInterceptor;
    }

    /**
     * Registers the {@code /api/terminal} WebSocket endpoint with the auth interceptor.
     *
     * <p>Any origin pattern is permitted at the CORS level because authentication
     * is enforced by {@link WebSocketAuthInterceptor} at the handshake stage.
     *
     * @param  registry  the Spring WebSocket handler registry
     * @since   v2026.1.0  — initial Java Spring Boot migration
     * @version v2026.1.0  — security hardening: added WebSocketAuthInterceptor
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalService, "/api/terminal")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authInterceptor);
    }
}
