package com.ollanest.config;

import com.ollanest.service.TerminalService;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket endpoint registration and configuration for Olla Nest.
 *
 * <h3>Why this class exists</h3>
 * <p>Registers the {@code /api/terminal} WebSocket endpoint and wires the
 * {@link WebSocketAuthInterceptor} as a handshake guard so that every upgrade
 * request is authenticated and authorised before the shell process is spawned.
 * Keeping endpoint registration here (rather than inside {@link TerminalService})
 * follows the separation-of-concerns principle and makes the WebSocket topology
 * visible at a glance.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>{@code setAllowedOriginPatterns("*")} is used because the application may be
 *       accessed from different hostnames in development and air-gapped deployments;
 *       actual authentication is enforced by {@link WebSocketAuthInterceptor} at
 *       handshake time rather than by CORS origin matching.</li>
 *   <li>SockJS fallback is not added here because the frontend uses native WebSocket
 *       exclusively; adding it would complicate the terminal client unnecessarily.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial migration; replaces Node.js {@code ws} server endpoint
 *       registration</li>
 *   <li>v2026.1.4 — added {@link WebSocketAuthInterceptor} registration as part of
 *       the CRIT-1 security hardening pass</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Handles WebSocket messages and manages the terminal process lifecycle. */
    private final TerminalService terminalService;

    /** Intercepts WebSocket upgrade requests to enforce session authentication. */
    private final WebSocketAuthInterceptor authInterceptor;

    /**
     * Constructor-injects the terminal handler and the authentication interceptor.
     *
     * @param  terminalService  the WebSocket handler for in-browser terminal sessions
     * @param  authInterceptor  the handshake interceptor that enforces authentication
     *                          and the {@code workspace:build} right
     * @since  v2026.1.0
     */
    public WebSocketConfig(TerminalService terminalService, WebSocketAuthInterceptor authInterceptor) {
        this.terminalService = terminalService;
        this.authInterceptor = authInterceptor;
    }

    /**
     * Registers the {@code /api/terminal} WebSocket endpoint with the authentication
     * interceptor.
     *
     * <p>All origin patterns are permitted at the CORS level; security is enforced
     * by {@link WebSocketAuthInterceptor} at the handshake stage.
     *
     * @param  registry  the Spring WebSocket handler registry
     * @since  v2026.1.0
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalService, "/api/terminal")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authInterceptor);
    }
}
