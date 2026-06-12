package com.ollanest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.ollanest.model.User;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Unit tests for {@link MdcLoggingFilter}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The {@link MdcLoggingFilter} stamps every request thread with SLF4J
 * {@link MDC} context (user identity, HTTP method, path, request id, client IP)
 * so that downstream log lines are automatically correlated to a single
 * request and user. Getting this wrong leaks identity between pooled threads or
 * floods the logs with MDC work for static assets, so the behaviour is pinned
 * by these tests rather than left to manual inspection.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Uses {@link MockitoExtension} with mocked servlet objects so the filter
 * can be exercised without a running container.</li>
 * <li>MDC values written inside the chain are captured via {@code doAnswer}
 * callbacks because the filter clears the MDC in its {@code finally} block
 * before {@code doFilterInternal} returns.</li>
 * <li>{@link #clearMdc()} runs after every test to guarantee no MDC state
 * bleeds across test methods sharing the JUnit worker thread.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — initial MDC population, cleanup, IP-resolution and
 * static-asset bypass coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MdcLoggingFilter — unit tests")
class MdcLoggingFilterTest {

	/** Filter under test; stateless, so a single shared instance is safe. */
	private final MdcLoggingFilter filter = new MdcLoggingFilter();

	/** Mocked inbound request supplying headers, URI and the authenticated-user attribute. */
	@Mock
	HttpServletRequest req;
	/** Mocked response passed through the chain unmodified. */
	@Mock
	HttpServletResponse res;
	/** Mocked downstream chain used to observe MDC state mid-request and to simulate failures. */
	@Mock
	FilterChain chain;

	/**
	 * Clears the SLF4J {@link MDC} after each test.
	 *
	 * <p>
	 * JUnit reuses worker threads, and the filter only clears the keys it sets;
	 * an explicit clear here prevents any residual context from one test
	 * influencing assertions in the next.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	// ── MDC keys for authenticated user ──────────────────────────────────────

	/**
	 * Verifies that an authenticated request populates every identity and
	 * request MDC key with the expected values while the chain executes.
	 *
	 * <p>
	 * A {@link User} is placed in the {@code authenticatedUser} request
	 * attribute (as {@code SessionAuthFilter} would) and the MDC is snapshotted
	 * inside the chain callback. The test proves userId, userEmail, userRole,
	 * method and path are exact, and that a non-blank request id (a UUID) is
	 * present — confirming the filter binds full context before delegating.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("authenticated user populates userId, userEmail, userRole, method, path, requestId")
	void authenticatedUserPopulatesMdc() throws Exception {
		User user = new User();
		user.id = "u-123";
		user.email = "alice@example.com";
		user.role = "admin";

		// Stub: authenticated user placed in request attribute by SessionAuthFilter
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/state");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("127.0.0.1");

		// Capture MDC state inside the chain — we must read it before the filter clears
		// it
		final String[] capturedUserId = { null };
		final String[] capturedUserEmail = { null };
		final String[] capturedUserRole = { null };
		final String[] capturedRequestId = { null };
		final String[] capturedMethod = { null };
		final String[] capturedPath = { null };

		doAnswer(inv -> {
			capturedUserId[0] = MDC.get(MdcLoggingFilter.KEY_USER_ID);
			capturedUserEmail[0] = MDC.get(MdcLoggingFilter.KEY_USER_EMAIL);
			capturedUserRole[0] = MDC.get(MdcLoggingFilter.KEY_USER_ROLE);
			capturedRequestId[0] = MDC.get(MdcLoggingFilter.KEY_REQUEST_ID);
			capturedMethod[0] = MDC.get(MdcLoggingFilter.KEY_METHOD);
			capturedPath[0] = MDC.get(MdcLoggingFilter.KEY_PATH);
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

	/**
	 * Verifies that an unauthenticated request stamps the identity keys with the
	 * {@code "anon"} sentinel rather than leaving them null.
	 *
	 * <p>
	 * With no {@code authenticatedUser} attribute present, the captured userId,
	 * userEmail and userRole must all equal {@code "anon"} so that log queries
	 * can group anonymous traffic without tripping over null values.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
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

		final String[] userId = { null }, email = { null }, role = { null };
		doAnswer(inv -> {
			userId[0] = MDC.get(MdcLoggingFilter.KEY_USER_ID);
			email[0] = MDC.get(MdcLoggingFilter.KEY_USER_EMAIL);
			role[0] = MDC.get(MdcLoggingFilter.KEY_USER_ROLE);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		// Anonymous requests must use "anon" sentinel — never null — so log queries
		// work cleanly
		assertThat(userId[0]).isEqualTo("anon");
		assertThat(email[0]).isEqualTo("anon");
		assertThat(role[0]).isEqualTo("anon");
	}

	// ── MDC cleared after chain ───────────────────────────────────────────────

	/**
	 * Verifies the MDC is empty after the filter completes normally.
	 *
	 * <p>
	 * After {@code doFilterInternal} returns, both the request id and user id
	 * keys must read null, proving the {@code finally} cleanup runs and no
	 * context leaks onto the next request handled by the same pooled thread.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
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

		// MDC must be empty after the filter completes — no MDC leakage into the next
		// request
		assertThat(MDC.get(MdcLoggingFilter.KEY_REQUEST_ID)).isNull();
		assertThat(MDC.get(MdcLoggingFilter.KEY_USER_ID)).isNull();
	}

	/**
	 * Verifies the MDC is cleared even when the downstream chain throws.
	 *
	 * <p>
	 * The chain is stubbed to throw a {@link RuntimeException}; after the
	 * exception propagates and is swallowed, the request id key must still read
	 * null. This guards the {@code finally}-block cleanup that is critical for
	 * thread-pool reuse where a leaked MDC would mislabel later requests.
	 *
	 * @throws Exception if {@code doFilterInternal} throws a checked exception
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("MDC is cleared even when filter chain throws an exception")
	void mdcClearedEvenOnException() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		when(req.getHeader("X-Real-IP")).thenReturn(null);
		when(req.getRemoteAddr()).thenReturn("127.0.0.1");
		// Stub: downstream filter throws — MDC cleanup must still happen (finally
		// block)
		doThrow(new RuntimeException("downstream error")).when(chain).doFilter(req, res);

		try {
			filter.doFilterInternal(req, res, chain);
		} catch (RuntimeException ignored) {
		}

		// MDC must be cleared even after an exception — thread pool reuse makes this
		// critical
		assertThat(MDC.get(MdcLoggingFilter.KEY_REQUEST_ID)).isNull();
	}

	// ── IP resolution ─────────────────────────────────────────────────────────

	/**
	 * Verifies {@code X-Forwarded-For} wins over the socket remote address.
	 *
	 * <p>
	 * When the header carries a {@code "client, proxy"} list, the MDC IP key
	 * must hold the first (original client) entry, confirming the filter honours
	 * proxy-forwarding precedence and splits the comma list correctly.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("X-Forwarded-For header takes precedence over remote address")
	void xForwardedForTakesPrecedence() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		// Stub: X-Forwarded-For contains the real client IP followed by the proxy IP
		when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
		// X-Real-IP should not be called since X-Forwarded-For is set

		final String[] ip = { null };
		doAnswer(inv -> {
			ip[0] = MDC.get(MdcLoggingFilter.KEY_IP);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		// First IP in the comma-list is the original client IP
		assertThat(ip[0]).isEqualTo("203.0.113.5"); // first IP from comma-list
	}

	/**
	 * Verifies {@code X-Real-IP} is used when {@code X-Forwarded-For} is absent.
	 *
	 * <p>
	 * With no forwarded-for header but an {@code X-Real-IP} present, the MDC IP
	 * key must equal that value — covering reverse proxies that set only the
	 * single-IP header.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("X-Real-IP used when X-Forwarded-For is absent")
	void xRealIpFallback() throws Exception {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getRequestURI()).thenReturn("/api/health");
		when(req.getHeader("X-Forwarded-For")).thenReturn(null);
		// Stub: only X-Real-IP is available (set by some reverse proxies)
		when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.42");

		final String[] ip = { null };
		doAnswer(inv -> {
			ip[0] = MDC.get(MdcLoggingFilter.KEY_IP);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		assertThat(ip[0]).isEqualTo("198.51.100.42");
	}

	/**
	 * Verifies the socket remote address is the final IP fallback.
	 *
	 * <p>
	 * With neither forwarding header set, the MDC IP key must equal
	 * {@code getRemoteAddr()}, confirming the last rung of the resolution
	 * ladder.
	 *
	 * @throws Exception if {@code doFilterInternal} or the mocked chain throws
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
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

		final String[] ip = { null };
		doAnswer(inv -> {
			ip[0] = MDC.get(MdcLoggingFilter.KEY_IP);
			return null;
		}).when(chain).doFilter(req, res);

		filter.doFilterInternal(req, res, chain);

		assertThat(ip[0]).isEqualTo("192.168.1.10");
	}

	// ── shouldNotFilter ───────────────────────────────────────────────────────

	/**
	 * Verifies JavaScript assets bypass the filter.
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code true} for a {@code .js} URI so
	 * static chunks do not incur MDC bookkeeping on every fetch.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns true for .js files")
	void skipsJsFiles() {
		when(req.getRequestURI()).thenReturn("/assets/app.js");
		// Static assets must bypass the filter to avoid MDC overhead on every chunk
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	/**
	 * Verifies CSS assets bypass the filter.
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code true} for a {@code .css} URI,
	 * mirroring the JavaScript bypass.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns true for .css files")
	void skipsCssFiles() {
		when(req.getRequestURI()).thenReturn("/assets/styles.css");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	/**
	 * Verifies static HTML files bypass the filter.
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code true} for an {@code .html} URI
	 * so pre-rendered pages skip per-request MDC work.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns true for .html files")
	void skipsHtmlFiles() {
		when(req.getRequestURI()).thenReturn("/admin.html");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	/**
	 * Verifies favicon/icon files bypass the filter.
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code true} for an {@code .ico} URI.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns true for .ico files")
	void skipsIcoFiles() {
		when(req.getRequestURI()).thenReturn("/favicon.ico");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	/**
	 * Verifies bundled vendor paths bypass the filter.
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code true} for any {@code /vendor/}
	 * path so third-party bundles are treated as static assets.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns true for /vendor/ paths")
	void skipsVendorPaths() {
		when(req.getRequestURI()).thenReturn("/vendor/some-lib.min.js");
		assertThat(filter.shouldNotFilter(req)).isTrue();
	}

	/**
	 * Verifies API paths are filtered (not skipped).
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code false} for an {@code /api/...}
	 * URI so every API request receives a request id and identity context in its
	 * log lines.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns false for API paths")
	void doesNotSkipApiPaths() {
		when(req.getRequestURI()).thenReturn("/api/auth/me");
		// API paths must be filtered so every request gets a requestId in logs
		assertThat(filter.shouldNotFilter(req)).isFalse();
	}

	/**
	 * Verifies the {@code /admin} route is filtered (not skipped).
	 *
	 * <p>
	 * {@code shouldNotFilter} must return {@code false} for {@code /admin}; it is
	 * a dynamic route, not a static file, so it must carry MDC context.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("shouldNotFilter returns false for /admin path")
	void doesNotSkipAdminPath() {
		when(req.getRequestURI()).thenReturn("/admin");
		// /admin is not a static file — it must be filtered
		assertThat(filter.shouldNotFilter(req)).isFalse();
	}
}
