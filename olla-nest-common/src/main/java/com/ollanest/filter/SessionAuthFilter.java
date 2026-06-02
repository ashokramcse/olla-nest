package com.ollanest.filter;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@link OncePerRequestFilter} that authenticates every HTTP request via the
 * Olla Nest session cookie.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Spring Security's default form-login and HTTP-Basic authentication mechanisms
 * are disabled in Olla Nest. Instead, all authentication is cookie-based: the
 * client receives an {@code olla_nest_session} cookie on login and sends it
 * with every subsequent request. This filter reads that cookie, delegates
 * validation to {@link AuthService#getSessionUser}, and — on success — stores
 * the resolved {@link User} as a request attribute that controllers can read
 * without touching the session store again.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The filter never short-circuits the request for unauthenticated paths;
 * access control is enforced by {@code BaseController.requireAuth()} and
 * {@code BaseController.requireAdmin()}.</li>
 * <li>Session tokens are validated by {@link AuthService}, which caches them in
 * a {@code ConcurrentHashMap} to avoid a DB round-trip on every request.</li>
 * <li>The following paths are intentionally served without a user attribute:
 * {@code POST /api/auth/login} and {@code GET /api/bootstrap}.</li>
 * <li>{@code @Order(1)} ensures this filter runs before
 * {@code MdcLoggingFilter} ({@code @Order(2)}) so the MDC context always has
 * an authenticated user available when log entries are written (HIGH-5).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from Node.js session-cookie middleware</li>
 * <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * <li>v2026.1.10 — HIGH-5: added {@code @Order(1)} to guarantee this filter
 * executes before MdcLoggingFilter (@Order(2))</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10
 */
@Order(1)
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

	/** Resolves and validates session cookies against the session store. */
	private final AuthService authService;
	private final com.ollanest.service.UserService userService;

	public SessionAuthFilter(AuthService authService, com.ollanest.service.UserService userService) {
		this.authService = authService;
		this.userService = userService;
	}

	/**
	 * Extracts and validates the session cookie, then sets the authenticated user
	 * as a request attribute before passing the request down the filter chain.
	 *
	 * <p>
	 * If no valid session cookie is present, or if the session has expired, no
	 * attribute is set and the request continues unauthenticated. Controllers are
	 * responsible for returning {@code 401} for protected endpoints.
	 *
	 * @param request     the incoming HTTP request
	 * @param response    the HTTP response
	 * @param filterChain the remaining filter chain to invoke
	 * @throws ServletException if the next filter throws a servlet error
	 * @throws IOException      if an I/O error occurs during filter chaining
	 * @since v2026.1.0
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		// 1. Try cookie-based session (primary path)
		User user = authService.getSessionUser(request);
		if (user != null) {
			request.setAttribute("authenticatedUser", user);
			filterChain.doFilter(request, response);
			return;
		}

		// 2. Try Bearer token (API access via oly_ prefixed tokens)
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer oly_")) {
			String rawToken = authHeader.substring("Bearer ".length()).trim();
			try {
				// Lazy-load ApiTokenService to avoid circular dependency
				com.ollanest.service.ApiTokenService tokenService =
						getApplicationContext(request).getBean(com.ollanest.service.ApiTokenService.class);
				if (tokenService != null) {
					java.util.Map<String, Object> token = tokenService.validate(rawToken);
					if (token != null) {
						String owner = (String) token.get("owner");
						User tokenUser = userService != null ? userService.findUserByEmail(owner) : null;
						if (tokenUser == null) {
							// Create a synthetic user record for the token owner
							tokenUser = new User();
							tokenUser.id = owner;
							tokenUser.name = owner;
							tokenUser.role = "user";
							tokenUser.rights = java.util.List.of("chat:use");
						}
						request.setAttribute("authenticatedUser", tokenUser);
						request.setAttribute("api_token", true);
						request.setAttribute("api_token_owner", owner);
					}
				}
			} catch (Exception ignore) {
				// Token validation failure — continue unauthenticated
			}
		}

		filterChain.doFilter(request, response);
	}

	private org.springframework.context.ApplicationContext getApplicationContext(
			jakarta.servlet.http.HttpServletRequest request) {
		try {
			return org.springframework.web.context.support.WebApplicationContextUtils
					.getWebApplicationContext(request.getServletContext());
		} catch (Exception e) {
			return null;
		}
	}
}
