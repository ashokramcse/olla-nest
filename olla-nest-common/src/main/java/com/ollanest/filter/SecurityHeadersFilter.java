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
 * improvement.
 * TODO (tech-debt): replace {@code 'unsafe-inline'} on script-src with a
 * nonce-based CSP to eliminate XSS risk from injected inline scripts.</li>
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
 * <li>v2026.1.10 — CRIT-5: changed Permissions-Policy microphone from () to
 * (self) so voice recording works; MED-6: removed deprecated
 * X-XSS-Protection header (harmful in modern browsers)</li>
 * </ul>
 *
 * <p>
 * Headers applied on every response:
 * <ul>
 * <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type
 * sniffing</li>
 * <li>{@code X-Frame-Options: DENY} — prevents clickjacking</li>
 * <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — limits
 * referrer leakage</li>
 * <li>{@code Content-Security-Policy} — restricts resource origins (MED-1)</li>
 * <li>{@code Strict-Transport-Security} — enforces HTTPS for 1 year
 * (MED-2)</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10
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
		// MED-6 FIX: Prevent sensitive API responses from being cached by proxies or browsers.
		// Applied to /api/** only; static assets can be cached by the reverse proxy.
		String path = request.getRequestURI();
		if (path != null && path.startsWith("/api/")) {
			response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
			response.setHeader("Pragma", "no-cache");
		}
		// MED-6: X-XSS-Protection removed — the header is deprecated and can cause
		// unintended page-blocking in modern browsers (Chromium removed the XSS
		// Auditor; Firefox/Edge never supported it). CSP is the correct mitigation.
		response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
		// CRIT-5: microphone=(self) allows voice recording from the same origin;
		// camera, geolocation, and payment remain fully disabled (hardening MED-3).
		response.setHeader("Permissions-Policy", "camera=(), microphone=(self), geolocation=(), payment=()");
		// TODO (tech-debt): remove 'unsafe-inline' from script-src and replace with
		// a nonce-based CSP once the frontend no longer uses inline event handlers.
		response.setHeader("Content-Security-Policy",
				"default-src 'self'; "
						+ "script-src 'self' 'unsafe-inline'; "
						// Fonts are self-hosted (see public/fonts.css) — no external
						// font/style origins needed, tightening the CSP.
						+ "style-src 'self' 'unsafe-inline'; "
						+ "img-src 'self' data: blob:; "
						+ "connect-src 'self' ws: wss:; "
						+ "font-src 'self' data:; "
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
