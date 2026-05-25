package com.ollanest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@link OncePerRequestFilter} that adds HTTP security headers to every
 * response.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Spring Security's default security-header set is disabled in
 * {@link com.ollanest.config.SecurityConfig} so that Olla Nest can own the
 * exact header values rather than relying on framework defaults. This filter
 * runs before all other filters in the chain and applies a curated set of
 * headers that harden the application against common browser-based attacks.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@code 'unsafe-inline'} is permitted for scripts and styles because the
 * frontend uses inline event handlers; a nonce-based CSP is a future
 * improvement.</li>
 * <li>The filter is registered via {@link Component} so Spring Boot
 * auto-detects it, but its position in the security filter chain is controlled
 * explicitly in {@link com.ollanest.config.SecurityConfig#filterChain}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from Node.js security-header
 * middleware</li>
 * <li>v2026.1.4 — added {@code Content-Security-Policy} (MED-1) and
 * {@code Strict-Transport-Security} (MED-2) headers as part of the security
 * hardening pass</li>
 * </ul>
 *
 * <p>
 * Headers applied on every response:
 * <ul>
 * <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type
 * sniffing</li>
 * <li>{@code X-Frame-Options: SAMEORIGIN} — prevents clickjacking</li>
 * <li>{@code X-XSS-Protection: 1; mode=block} — legacy XSS filter hint</li>
 * <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — limits
 * referrer leakage</li>
 * <li>{@code Content-Security-Policy} — restricts resource origins (MED-1)</li>
 * <li>{@code Strict-Transport-Security} — enforces HTTPS for 1 year
 * (MED-2)</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

	/**
	 * Adds all security headers to the HTTP response, then passes control to the
	 * next filter in the chain.
	 *
	 * @param request     the incoming HTTP request
	 * @param response    the HTTP response to which headers are added
	 * @param filterChain the remaining filter chain to invoke
	 * @throws ServletException if the next filter throws a servlet error
	 * @throws IOException      if an I/O error occurs during filter chaining
	 * @since v2026.1.0
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		response.setHeader("X-Content-Type-Options", "nosniff");
		// DENY is stricter than SAMEORIGIN — the app never needs to iframe itself.
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("X-XSS-Protection", "1; mode=block");
		response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
		// Disable browser features the app never uses (hardening MED-3).
		response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
		response.setHeader("Content-Security-Policy",
				"default-src 'self'; "
						+ "script-src 'self' 'unsafe-inline'; "
						+ "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
						+ "img-src 'self' data: blob:; "
						+ "connect-src 'self' ws: wss:; "
						+ "font-src 'self' data: https://fonts.gstatic.com; "
						+ "frame-ancestors 'none'; "
						+ "base-uri 'self'; "
						+ "form-action 'self'");
		// HSTS only when the connection is already over HTTPS — setting it on
		// plain HTTP would poison the browser's HSTS cache for dev environments.
		if (request.isSecure()) {
			response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
		}
		filterChain.doFilter(request, response);
	}
}
