package com.ollanest.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST controller for the authentication lifecycle: login, logout, and session
 * status.
 *
 * <p>
 * Exposes three endpoints:
 * <ul>
 * <li>{@code POST /api/auth/login} — authenticate with email and password; sets
 * an HttpOnly session cookie on success</li>
 * <li>{@code POST /api/auth/logout} — invalidates the current session
 * cookie</li>
 * <li>{@code GET /api/auth/me} — returns authenticated status and the user
 * object</li>
 * </ul>
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Centralising the login flow in a dedicated controller keeps auth logic out of
 * the general-purpose {@code StateController} and makes it straightforward to
 * audit all credential-handling code in one place. The separation also
 * simplifies future work such as adding MFA or SSO flows without touching the
 * chat or model controllers.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Rate limit state is stored in the {@code login_attempts} DB table rather
 * than in-memory so it survives restarts and works correctly with multiple JVM
 * instances.</li>
 * <li>A dummy BCrypt hash is used for constant-time comparison when the email
 * is not found, preventing user-enumeration via timing attacks.</li>
 * <li>The same 401 message is returned for bad password, unknown email, and
 * expired account to prevent user enumeration.</li>
 * <li>A 1 % probabilistic cleanup of stale {@code login_attempts} rows runs on
 * successful login (L-18 fix) to prevent unbounded table growth without a
 * dedicated scheduled job.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.0 — security hardening: enforce {@code access_expires_at}
 * (HIGH-2)</li>
 * <li>v2026.1.10 — L-18: probabilistic cleanup of stale login_attempts
 * rows</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController extends BaseController {

	/** Authentication business logic and session management. */
	private final AuthService authService;

	/** Used to build the public {@link User} object from a DB row. */
	private final UserService userService;

	/** Used to write audit log entries for login and logout events. */
	private final ChatService chatService;

	/** Direct DB access for rate-limit checks and user queries. */
	private final JdbcTemplate db;

	/** Maximum number of failed login attempts per IP within the window. */
	private static final int LOGIN_MAX_ATTEMPTS = 10;

	/**
	 * Dummy BCrypt hash used for constant-time comparison when the email is not
	 * found. Without this, an attacker can enumerate valid emails by timing how
	 * long the login endpoint takes: a missing-user response returns immediately
	 * (no BCrypt), while a wrong-password response takes ~100 ms (BCrypt work). By
	 * always calling {@code BCrypt.checkpw} against this sentinel hash we equalise
	 * response times for both failure modes.
	 */
	private static final String DUMMY_HASH = BCrypt.hashpw("dummy-constant-time-sentinel", BCrypt.gensalt(10));

	/** Rate-limit window duration in milliseconds (15 minutes). */
	private static final long LOGIN_WINDOW_MS = 15 * 60 * 1000;

	/**
	 * Optional IP address of a trusted reverse proxy. When set, the
	 * {@code X-Forwarded-For} header is used to extract the real client IP for rate
	 * limiting. If blank, {@code remoteAddr} is used directly.
	 */
	@Value("${app.trusted-proxy:}")
	private String trustedProxy;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param authService the authentication and session service
	 * @param userService the user lookup service
	 * @param chatService the audit log helper
	 * @param db          the JDBC template for rate-limit queries
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AuthController(AuthService authService, UserService userService, ChatService chatService, JdbcTemplate db) {
		this.authService = authService;
		this.userService = userService;
		this.chatService = chatService;
		this.db = db;
	}

	/**
	 * Authenticates the user with email and password, issuing a session cookie on
	 * success.
	 *
	 * <p>
	 * Processing steps:
	 * <ol>
	 * <li>Resolve the client IP (optionally from {@code X-Forwarded-For} via
	 * trusted proxy).</li>
	 * <li>Check the {@code login_attempts} table; return 429 if the IP is over the
	 * limit.</li>
	 * <li>Query the user row with active + non-expired access constraints.</li>
	 * <li>BCrypt-verify the supplied password against the stored hash.</li>
	 * <li>On success: clear rate-limit record, set session cookie, write audit
	 * event.</li>
	 * <li>On failure: increment rate-limit counter, return 401.</li>
	 * </ol>
	 *
	 * @param body request body with {@code email} (String) and {@code password}
	 *             (String)
	 * @param req  the current HTTP request (for IP extraction)
	 * @param res  the HTTP response (session cookie is set here on success)
	 * @return 200 OK with {@code {ok, user, redirectTo}} on success; 400 for
	 *         missing fields; 401 for bad credentials; 429 for rate limit
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: enforce access_expires_at (HIGH-2)
	 */
	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest req,
			HttpServletResponse res) {
		String remoteAddr = req.getRemoteAddr() != null ? req.getRemoteAddr() : "unknown";
		String ip;
		if (trustedProxy != null && !trustedProxy.isBlank() && trustedProxy.equals(remoteAddr)) {
			String forwarded = req.getHeader("x-forwarded-for");
			ip = forwarded != null ? forwarded.split(",")[0].trim() : remoteAddr;
		} else {
			ip = remoteAddr;
		}
		long now = System.currentTimeMillis();

		// Rate limit check
		List<Map<String, Object>> attempts = db.queryForList("SELECT count, reset_at FROM login_attempts WHERE ip = ?",
				ip);
		long count = 0;
		long resetAt = now + LOGIN_WINDOW_MS;
		if (!attempts.isEmpty()) {
			count = ((Number) attempts.get(0).get("count")).longValue();
			resetAt = ((Number) attempts.get(0).get("reset_at")).longValue();
			if (now > resetAt) {
				count = 0;
				resetAt = now + LOGIN_WINDOW_MS;
			}
		}
		if (count >= LOGIN_MAX_ATTEMPTS) {
			long retryAfter = (resetAt - now) / 1000;
			return ResponseEntity.status(429).body(Map.of("error",
					"Too many login attempts. Try again in " + (int) Math.ceil(retryAfter / 60.0) + " minutes."));
		}

		String email = (String) body.get("email");
		String password = (String) body.get("password");
		if (email == null || password == null || email.isBlank() || password.isBlank()) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", "Email and password are required"));
		}
		// Guard against BCrypt DoS: BCrypt is O(n) on password length; a 72-byte
		// limit is the effective max anyway (bcryptjs / OpenBSD truncate silently).
		// Email max 320 chars per RFC 5321.
		if (email.length() > 320 || password.length() > 1024) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", "Email and password are required"));
		}

		// Query user row; also enforce access_expires_at (HIGH-2)
		List<Map<String, Object>> rows = db
				.queryForList("SELECT id, name, email, role, rights, department_id, active, employee_id, "
						+ "designation, team, branch, manager, organization, ai_access_tier, "
						+ "daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, "
						+ "concurrent_model_limit, api_rate_limit_per_minute, max_context_size, "
						+ "mfa_enabled, security_risk_score, access_status, access_expires_at, "
						+ "last_active_at, auth_provider, phone, avatar_initials, password_hash "
						+ "FROM users WHERE email = ? AND active = 1 " + "AND auth_provider = 'local' "
						+ "AND (access_expires_at IS NULL OR access_expires_at = '' "
						+ "OR access_expires_at > datetime('now'))", email);

		Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
		String storedHash = row != null ? (String) row.get("password_hash") : null;

		// Always invoke BCrypt — even when the user is not found — to ensure response
		// time is constant regardless of whether the email exists. Without this,
		// an attacker can enumerate valid accounts via timing differences (user-missing
		// returns immediately; wrong-password takes ~100 ms for BCrypt work).
		String hashToCheck = (storedHash != null) ? storedHash : DUMMY_HASH;
		boolean passwordMatch = BCrypt.checkpw(password, hashToCheck);

		if (row == null || storedHash == null || !passwordMatch) {
			// Increment attempts — same message for all failure modes (prevents user
			// enumeration)
			db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)", ip, count + 1,
					resetAt);
			// SOC 2: log security event for failed login (audit trail) — no user PII in
			// the message since the email may not correspond to any real account.
			chatService.appendAudit("system", "auth.login.failed", "Failed login attempt from " + ip, Map.of("ip", ip));
			return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Invalid email or password"));
		}

		// Success — clear rate-limit record and issue session
		db.update("DELETE FROM login_attempts WHERE ip = ?", ip);

		// Probabilistic cleanup of stale login_attempts rows (fires ~1% of requests)
		if (Math.random() < 0.01) {
			try {
				db.update("DELETE FROM login_attempts WHERE reset_at < ?", now - 86_400_000L); // 24h ago
			} catch (Exception ignored) {
			}
		}

		User user = userService.publicUser(row);
		authService.setSession(res, req, user);
		// Include IP in audit log for SOC 2 traceability
		chatService.appendAudit(user.name, "auth.login", "User signed in from " + ip,
				Map.of("ip", ip, "role", user.role));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("user", user);
		result.put("redirectTo", "admin".equals(user.role) ? "/admin" : "/app");
		return ResponseEntity.ok(result);
	}

	/**
	 * Invalidates the current session and clears the session cookie.
	 *
	 * <p>
	 * Requires the {@code X-Requested-With} CSRF header to prevent CSRF-driven
	 * logout. Returns 403 if the header is absent.
	 *
	 * @param req the current HTTP request (for token extraction and CSRF check)
	 * @param res the HTTP response (session cookie is cleared here)
	 * @return 200 OK {@code {ok: true}} on success, or 403 if CSRF header missing
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/logout")
	public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req, HttpServletResponse res) {
		if (req.getHeader("x-requested-with") == null) {
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", "Forbidden"));
		}
		String token = authService.getToken(req);
		authService.clearSession(res, token);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Returns the current authentication state and user object.
	 *
	 * <p>
	 * This is an unauthenticated-safe endpoint — it returns
	 * {@code {authenticated: false, user: null}} for unauthenticated requests
	 * rather than 401, so the frontend can poll it to refresh login state.
	 *
	 * @param req the current HTTP request
	 * @return 200 OK with {@code {authenticated: boolean, user: User|null}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/me")
	public ResponseEntity<Map<String, Object>> me(HttpServletRequest req) {
		User user = getUser(req);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("authenticated", user != null);
		result.put("user", user);
		return ResponseEntity.ok(result);
	}
}
