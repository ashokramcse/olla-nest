package com.ollanest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.ollanest.service.TerminalService;

/**
 * WebSocket endpoint registration and configuration for Olla Nest.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Registers the {@code /api/terminal} WebSocket endpoint and wires the
 * {@link WebSocketAuthInterceptor} as a handshake guard so that every upgrade
 * request is authenticated and authorised before the shell process is spawned.
 * Keeping endpoint registration here (rather than inside
 * {@link TerminalService}) follows the separation-of-concerns principle and
 * makes the WebSocket topology visible at a glance.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@code app.base-url} is used as the allowed WebSocket origin so that
 * production deployments are locked to the configured base URL (HIGH-7).
 * Defaults to {@code *} when not set, which permits all origins — only
 * acceptable in local development; always set {@code app.base-url} in
 * production.</li>
 * <li>SockJS fallback is not added here because the frontend uses native
 * WebSocket exclusively; adding it would complicate the terminal client
 * unnecessarily.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration; replaces Node.js {@code ws} server
 * endpoint registration</li>
 * <li>v2026.1.4 — added {@link WebSocketAuthInterceptor} registration as part
 * of the CRIT-1 security hardening pass</li>
 * <li>v2026.1.10 — HIGH-7: replaced hard-coded {@code "*"} with
 * {@code app.base-url} property; wildcard is only the fallback for dev
 * mode</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	/** Handles WebSocket messages and manages the terminal process lifecycle. */
	private final TerminalService terminalService;

	/** Intercepts WebSocket upgrade requests to enforce session authentication. */
	private final WebSocketAuthInterceptor authInterceptor;

	/**
	 * The base URL of the application, used to restrict WebSocket origin.
	 * <p>
	 * WARNING: In production, always set {@code app.base-url} (e.g.
	 * {@code https://olla.example.com}) in application properties. Leaving it unset
	 * falls back to {@code "*"} which permits WebSocket connections from any origin
	 * and is NOT safe for production deployments.
	 */
	@Value("${app.base-url:*}")
	private String appBaseUrl;

	/**
	 * Constructor-injects the terminal handler and the authentication interceptor.
	 *
	 * @param terminalService the WebSocket handler for in-browser terminal sessions
	 * @param authInterceptor the handshake interceptor that enforces authentication
	 *                        and the {@code workspace:build} right
	 * @since v2026.1.0
	 */
	public WebSocketConfig(TerminalService terminalService, WebSocketAuthInterceptor authInterceptor) {
		this.terminalService = terminalService;
		this.authInterceptor = authInterceptor;
	}

	/**
	 * Registers the {@code /api/terminal} WebSocket endpoint with the
	 * authentication interceptor.
	 *
	 * <p>
	 * The allowed origin pattern is read from {@code app.base-url}; security is
	 * also enforced by {@link WebSocketAuthInterceptor} at the handshake stage.
	 *
	 * @param registry the Spring WebSocket handler registry
	 * @since v2026.1.0
	 */
	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(terminalService, "/api/terminal").setAllowedOriginPatterns(appBaseUrl)
				.addInterceptors(authInterceptor);
	}
}
