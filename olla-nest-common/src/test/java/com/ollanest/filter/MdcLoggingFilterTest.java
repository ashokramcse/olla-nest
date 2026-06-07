package com.ollanest.filter;

import com.ollanest.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MdcLoggingFilter}.
 *
 * <p>Verifies MDC population for authenticated and anonymous requests,
 * MDC cleanup after the chain, IP resolution priority, and the
 * {@code shouldNotFilter} static-asset bypass.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MdcLoggingFilter — unit tests")
class MdcLoggingFilterTest {

	private final MdcLoggingFilter filter = new MdcLoggingFilter();

	@Mock HttpServletRequest  req;
	@Mock HttpServletResponse res;
	@Mock FilterChain         chain;

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	// ── MDC keys for authenticated user ──────────────────────────────────────

	@Test
	@DisplayName("authenticated user populates userId, userEmail, userRole, method, path, requestId")
	void authenticatedUserPopulatesMdc() throws Exception {
		User user = new User();
		user.id    = "u-123";
		user.email = "alice@example.com";
		user.role  = "admin";

		// Stub: authenticated user placed in request attribute by SessionAuthFilter
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/state");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("127.0.0.1");

		// Capture MDC state inside the chain — we must read it before the filter clears it
		final String[] capturedUserId    = {null};
		final String[] capturedUserEmail = {null};
		final String[] capturedUserRole  = {null};
		final String[] capturedRequestId = {null};
		final String[] capturedMethod    = {null};
		final String[] capturedPath      = {null};

		doAnswer(inv -> {
			capturedUserId[0]    = MDC.get(MdcLoggingFilter.KEY_USER_ID);
			capturedUserEmail[0] = MDC.get(MdcLoggingFilter.KEY_USER_EMAIL);
			capturedUserRole[0]  = MDC.get(MdcLoggingFilter.KEY_USER_ROLE);
			capturedRequestId[0] = MDC.get(MdcLoggingFilter.KEY_REQUEST_ID);
			capturedMethod[0]    = MDC.get(MdcLoggingFilter.KEY_METHOD);
			capturedPath[0]      = MDC.get(MdcLoggingFilter.KEY_PATH);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		// All MDC keys must be populated with the correct values during chain execution
		assertThat(capturedUserId[0]).isEqualTo("u-123");
		assertThat(capturedUserEmail[0]).isEqualTo("alice@example.com");
		assertThat(capturedUserRole[0]).isEqualTo("admin");
		assertThat(capturedRequestId[0]).isNotBlank(); // UUID — just check presence
		assertThat(capturedMethod[0]).isEqualTo("GET");
		assertThat(capturedPath[0]).isEqualTo("/api/state");
	}

	// ── MDC keys for anonymous user ───────────────────────────────────────────

	@Test
	@DisplayName("unauthenticated request sets userId/userEmail/userRole to 'anon'")
	void anonymousRequestSetsAnonKeys() throws Exception {
		// Stub: no authenticated user — anonymous request (e.g. login attempt)
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("POST");
		when(req.getRequestURI()).thenReturn("/api/auth/login");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("10.0.0.1");

		final String[] userId = {null}, email = {null}, role = {null};
		doAnswer(inv -> {
			userId[0] = MDC.get(MdcLoggingFilter.KEY_USER_ID);
			email[0]  = MDC.get(MdcLoggingFilter.KEY_USER_EMAIL);
			role[0]   = MDC.get(MdcLoggingFilter.KEY_USER_ROLE);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		// Anonymous requests must use "anon" sentinel — never null — so log queries work cleanly
		assertThat(userId[0]).isEqualTo("anon");
		assertThat(email[0]).isEqualTo("anon");
		assertThat(role[0]).isEqualTo("anon");
	}

	// ── MDC cleared after chain ───────────────────────────────────────────────

	@Test
	@DisplayName("MDC is cleared after filter chain completes")
	void mdcClearedAfterChain() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("127.0.0.1");

		filter.doFilterInternal(req, res, chain);

		// MDC must be empty after the filter completes — no MDC leakage into the next request
		assertThat(MDC.get(MdcLoggingFilter.KEY_REQUEST_ID)).isNull();
		assertThat(MDC.get(MdcLoggingFilter.KEY_USER_ID)).isNull();
	}

	@Test
	@DisplayName("MDC is cleared even when filter chain throws an exception")
	void mdcClearedEvenOnException() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("127.0.0.1");
		// Stub: downstream filter throws — MDC cleanup must still happen (finally block)
		doThrow(new RuntimeException("downstream error")).when(chain).doFilter(req, res);

		try {
			filter.doFilterInternal(req, res, chain);
		} catch (RuntimeException ignored) {}

		// MDC must be cleared even after an exception — thread pool reuse makes this critical
		assertThat(MDC.get(MdcLoggingFilter.KEY_REQUEST_ID)).isNull();
	}

	// ── IP resolution ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("X-Forwarded-For header takes precedence over remote address")
	void xForwardedForTakesPrecedence() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		// Stub: X-Forwarded-For contains the real client IP followed by the proxy IP
		when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
		// X-Real-IP should not be called since X-Forwarded-For is set

		final String[] ip = {null};
		doAnswer(inv -> { ip[0] = MDC.get(MdcLoggingFilter.KEY_IP); return null; }).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		// First IP in the comma-list is the original client IP
		assertThat(ip[0]).isEqualTo("203.0.113.5"); // first IP from comma-list
	}

	@Test
	@DisplayName("X-Real-IP used when X-Forwarded-For is absent")
	void xRealIpFallback() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		// Stub: only X-Real-IP is available (set by some reverse proxies)
		when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.42");

		final String[] ip = {null};
		doAnswer(inv -> { ip[0] = MDC.get(MdcLoggingFilter.KEY_IP); return null; }).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		assertThat(ip[0]).isEqualTo("198.51.100.42");
	}

	@Test
	@DisplayName("remote address used as final fallback")
	void remoteAddressFinalFallback() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		// Stub: neither forwarding header is present — use socket remote addr
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("192.168.1.10");

		final String[] ip = {null};
		doAnswer(inv -> { ip[0] = MDC.get(MdcLoggingFilter.KEY_IP); return null; }).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		assertThat(ip[0]).isEqualTo("192.168.1.10");
	}

	// ── shouldNotFilter ───────────────────────────────────────────────────────

	@Test
	@DisplayName("shouldNotFilter returns true for .js files")
	void skipsJsFiles() {
		when(req.getRequestURI()).thenReturn("/assets/app.js");
		// Static assets must bypass the filter to avoid MDC overhead on every chunk
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	@Test
	@DisplayName("shouldNotFilter returns true for .css files")
	void skipsCssFiles() {
		when(req.getRequestURI()).thenReturn("/assets/styles.css");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	@Test
	@DisplayName("shouldNotFilter returns true for .html files")
	void skipsHtmlFiles() {
		when(req.getRequestURI()).thenReturn("/admin.html");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	@Test
	@DisplayName("shouldNotFilter returns true for .ico files")
	void skipsIcoFiles() {
		when(req.getRequestURI()).thenReturn("/favicon.ico");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	@Test
	@DisplayName("shouldNotFilter returns true for /vendor/ paths")
	void skipsVendorPaths() {
		when(req.getRequestURI()).thenReturn("/vendor/some-lib.min.js");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	@Test
	@DisplayName("shouldNotFilter returns false for API paths")
	void doesNotSkipApiPaths() {
		when(req.getRequestURI()).thenReturn("/api/auth/me");
		// API paths must be filtered so every request gets a requestId in logs
		assertThat(filter.shouldNotFilter(req)).isFalse();
	}

	@Test
	@DisplayName("shouldNotFilter returns false for /admin path")
	void doesNotSkipAdminPath() {
		when(req.getRequestURI()).thenReturn("/admin");
		// /admin is not a static file — it must be filtered
		assertThat(filter.shouldNotFilter(req)).isFalse();
	}
}
