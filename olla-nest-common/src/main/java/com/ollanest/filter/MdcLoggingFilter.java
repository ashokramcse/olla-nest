package com.ollanest.filter;

import com.ollanest.model.User;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that populates SLF4J {@link MDC} with per-request context so
 * every log line emitted during a request automatically carries structured
 * metadata — without callers needing to pass context manually.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * When logs are shipped to Grafana Loki, each MDC key becomes a queryable
 * field. Operators can instantly answer:
 * <ul>
 * <li>"Show all ERROR logs for user {@code joe@company.com}"</li>
 * <li>"Show every request made to {@code /api/chat} in the last hour"</li>
 * <li>"Show all admin actions in the last 24 hours"</li>
 * <li>"Trace a single request end-to-end by {@code requestId}"</li>
 * </ul>
 *
 * <h3>MDC keys set</h3>
 * <table border="1">
 * <tr><th>Key</th><th>Source</th><th>Example value</th></tr>
 * <tr><td>{@code requestId}</td><td>UUID generated per request</td><td>{@code a3f1c2d4-…}</td></tr>
 * <tr><td>{@code userId}</td><td>{@code authenticatedUser} request attribute</td><td>{@code 42} / {@code anon}</td></tr>
 * <tr><td>{@code userEmail}</td><td>{@code authenticatedUser} request attribute</td><td>{@code joe@co.com} / {@code anon}</td></tr>
 * <tr><td>{@code userRole}</td><td>{@code authenticatedUser} request attribute</td><td>{@code admin} / {@code user} / {@code anon}</td></tr>
 * <tr><td>{@code method}</td><td>HTTP method</td><td>{@code POST}</td></tr>
 * <tr><td>{@code path}</td><td>Request URI (no query string)</td><td>{@code /api/chat}</td></tr>
 * <tr><td>{@code ip}</td><td>{@code X-Forwarded-For} → {@code X-Real-IP} → remote addr</td><td>{@code 203.0.113.5}</td></tr>
 * </table>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs <em>after</em> {@link SessionAuthFilter} (order 2 vs order 1) so
 * the {@code authenticatedUser} attribute is already set when this filter
 * reads it.</li>
 * <li>MDC is always cleared in a {@code finally} block — no thread-local
 * leakage across requests on pooled Tomcat threads.</li>
 * <li>Static assets ({@code .js}, {@code .css}, {@code .html}, favicons) are
 * skipped to avoid polluting logs with noise.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.9</b> — initial creation; Grafana + Loki structured logging.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.9
 * @version v2026.1.9
 */
@Component
@Order(2)
public class MdcLoggingFilter extends OncePerRequestFilter {

	// ── MDC key constants ─────────────────────────────────────────────────────

	/** Unique ID for the current HTTP request. */
	public static final String KEY_REQUEST_ID  = "requestId";

	/** Authenticated user's database ID, or {@code "anon"} for unauthenticated requests. */
	public static final String KEY_USER_ID     = "userId";

	/** Authenticated user's e-mail address, or {@code "anon"}. */
	public static final String KEY_USER_EMAIL  = "userEmail";

	/** Authenticated user's role ({@code "admin"} / {@code "user"} / {@code "anon"}). */
	public static final String KEY_USER_ROLE   = "userRole";

	/** HTTP method (GET, POST, …). */
	public static final String KEY_METHOD      = "method";

	/** Request URI path (no query string). */
	public static final String KEY_PATH        = "path";

	/** Client IP address. */
	public static final String KEY_IP          = "ip";

	// ── Filter ───────────────────────────────────────────────────────────────

	/**
	 * Populates MDC before the request is processed and clears it afterward.
	 *
	 * @param request     the incoming HTTP request
	 * @param response    the HTTP response
	 * @param filterChain the remaining filter chain
	 * @throws ServletException if the downstream filter throws
	 * @throws IOException      if an I/O error occurs
	 * @since v2026.1.9
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		try {
			populateMdc(request);
			filterChain.doFilter(request, response);
		} finally {
			MDC.clear();
		}
	}

	/**
	 * Returns {@code true} for static asset paths that do not need MDC tracing.
	 * Skipping these keeps Loki free of high-volume, low-value entries.
	 *
	 * @param request the HTTP request to evaluate
	 * @return {@code true} if this request should be skipped
	 * @since v2026.1.9
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.endsWith(".js")
				|| path.endsWith(".css")
				|| path.endsWith(".html")
				|| path.endsWith(".ico")
				|| path.endsWith(".svg")
				|| path.endsWith(".png")
				|| path.endsWith(".woff2")
				|| path.startsWith("/vendor/");
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/**
	 * Writes all MDC keys for the current request.
	 *
	 * @param request the incoming HTTP request
	 * @since v2026.1.9
	 */
	private void populateMdc(HttpServletRequest request) {
		// Every request gets a unique trace ID
		MDC.put(KEY_REQUEST_ID, UUID.randomUUID().toString());

		// User context — set by SessionAuthFilter before this filter runs
		User user = (User) request.getAttribute("authenticatedUser");
		if (user != null) {
			MDC.put(KEY_USER_ID,    user.id != null ? user.id : "unknown");
			MDC.put(KEY_USER_EMAIL, user.email != null ? user.email : "unknown");
			MDC.put(KEY_USER_ROLE,  user.role != null ? user.role : "user");
		} else {
			MDC.put(KEY_USER_ID,    "anon");
			MDC.put(KEY_USER_EMAIL, "anon");
			MDC.put(KEY_USER_ROLE,  "anon");
		}

		// Request metadata
		MDC.put(KEY_METHOD, request.getMethod());
		MDC.put(KEY_PATH,   request.getRequestURI());
		MDC.put(KEY_IP,     resolveClientIp(request));
	}

	/**
	 * Resolves the real client IP by checking proxy headers before falling back
	 * to the socket remote address.
	 *
	 * <p>Checks in order:
	 * <ol>
	 * <li>{@code X-Forwarded-For} — first IP in the comma-separated list</li>
	 * <li>{@code X-Real-IP}</li>
	 * <li>{@link HttpServletRequest#getRemoteAddr()}</li>
	 * </ol>
	 *
	 * @param request the HTTP request
	 * @return the best-effort client IP string
	 * @since v2026.1.9
	 */
	private String resolveClientIp(HttpServletRequest request) {
		String xff = request.getHeader("X-Forwarded-For");
		if (xff != null && !xff.isBlank()) {
			// X-Forwarded-For may be "client, proxy1, proxy2" — take the first
			return xff.split(",")[0].trim();
		}
		String xri = request.getHeader("X-Real-IP");
		if (xri != null && !xri.isBlank()) {
			return xri.trim();
		}
		return request.getRemoteAddr();
	}
}
