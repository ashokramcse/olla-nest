package com.ollanest.controller;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Handles user self-service account operations for the authenticated user.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Employees manage their own account without admin involvement — changing their
 * password, editing mutable profile fields, and viewing their token usage
 * against quota. This controller owns those self-service endpoints, all scoped
 * to the caller's own session.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint requires a valid session and CSRF header via
 * {@link BaseController#requireAuthWithCsrf}.</li>
 * <li>Profile updates use a field allowlist to prevent mass-assignment
 * attacks.</li>
 * <li>Enterprise users (non-local auth provider) may not edit designation,
 * team, or branch — those are owned by the identity provider.</li>
 * <li>Password changes are re-hashed with BCrypt cost 12.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * </ul>
 *
 * <pre>
 *   POST  /api/account/password — change own password (BCrypt cost 12)
 *   PATCH /api/account/profile  — update own mutable profile fields
 *   GET   /api/account/profile  — own profile incl. department name
 *   GET   /api/account/usage    — daily/monthly token usage vs limits
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
@RequestMapping("/api/account")
public class AccountController extends BaseController {

	/** JDBC template for direct DB queries (token usage, profile updates). */
	private final JdbcTemplate db;

	/** Used to load a fresh User object after profile updates. */
	private final UserService userService;

	/** Used to write audit log entries for password and profile changes. */
	private final ChatService chatService;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db          the JDBC template
	 * @param userService the user lookup service
	 * @param chatService the audit log helper
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AccountController(JdbcTemplate db, UserService userService, ChatService chatService) {
		this.db = db;
		this.userService = userService;
		this.chatService = chatService;
	}

	/**
	 * Changes the authenticated user's password.
	 *
	 * <p>
	 * Requires the current password to be supplied for verification. The new
	 * password must be at least 12 characters. On success, the new hash is stored
	 * with BCrypt cost 12 and an audit event is written.
	 *
	 * @param body request body with {@code currentPassword} and {@code newPassword}
	 * @param req  the current HTTP request (for auth and CSRF checks)
	 * @return 200 OK {@code {ok: true}} on success, or 400/401/404 on failure
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/password")
	public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		String newPassword = (String) body.get("newPassword");
		if (newPassword == null || newPassword.length() < 12) {
			return ResponseEntity.status(400).body(Map.of("error", "New password must be at least 12 characters"));
		}
		List<Map<String, Object>> rows = db.queryForList("SELECT password_hash FROM users WHERE id = ?", user.id);
		if (rows.isEmpty())
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		String storedHash = (String) rows.get(0).get("password_hash");
		String currentPassword = (String) body.get("currentPassword");
		if (storedHash == null || currentPassword == null || !BCrypt.checkpw(currentPassword, storedHash)) {
			return ResponseEntity.status(401).body(Map.of("error", "Current password is incorrect"));
		}
		db.update("UPDATE users SET password_hash = ? WHERE id = ?", BCrypt.hashpw(newPassword, BCrypt.gensalt(12)),
				user.id);
		chatService.appendAudit(user.name, "account.password.change", "Changed own password", null);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Updates the authenticated user's mutable profile fields.
	 *
	 * <p>
	 * Fields always allowed: {@code name}, {@code phone}, {@code avatar_initials}.
	 * Additional fields allowed for local-auth users only: {@code designation},
	 * {@code team}, {@code branch}. Enterprise (SSO) users may not change
	 * identity-provider-owned fields. Unknown or disallowed fields in the request
	 * body are silently ignored.
	 *
	 * @param body request body with camelCase field names and new values
	 * @param req  the current HTTP request (for auth and CSRF checks)
	 * @return 200 OK with {@code {ok: true, user: User}} on success, or 400/404
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PatchMapping("/profile")
	public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> rows = db.queryForList("SELECT auth_provider FROM users WHERE id = ?", user.id);
		if (rows.isEmpty())
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		String authProvider = (String) rows.get(0).getOrDefault("auth_provider", "local");
		boolean isEnterprise = !"local".equals(authProvider);

		List<String> allowed = new ArrayList<>(Arrays.asList("name", "phone", "avatar_initials"));
		if (!isEnterprise)
			allowed.addAll(Arrays.asList("designation", "team", "branch"));

		// Per-field maximum lengths — prevents unbounded string storage and protects
		// downstream systems that may truncate or choke on excessively long values.
		Map<String, Integer> maxLengths = new HashMap<>();
		maxLengths.put("name", 150);
		maxLengths.put("phone", 30);
		maxLengths.put("avatar_initials", 4);
		maxLengths.put("designation", 100);
		maxLengths.put("team", 100);
		maxLengths.put("branch", 100);

		List<String> setClauses = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String field : allowed) {
			String camel = snakeToCamel(field);
			if (!body.containsKey(camel))
				continue;
			// Sanitize and validate each user-supplied string value.
			// sanitizeText() strips HTML/XSS characters; length guard prevents
			// oversized payloads from reaching the DB.
			String raw = String.valueOf(body.get(camel));
			String sanitized = sanitizeText(raw);
			if (sanitized == null)
				sanitized = "";
			int maxLen = maxLengths.getOrDefault(field, 255);
			if (sanitized.length() > maxLen) {
				return ResponseEntity.status(400).body(
						Map.of("error", "Field '" + camel + "' exceeds maximum length of " + maxLen + " characters"));
			}
			setClauses.add(field + " = ?");
			values.add(sanitized);
		}
		if (setClauses.isEmpty()) {
			return ResponseEntity.status(400).body(Map.of("error", "No updatable fields provided"));
		}
		values.add(user.id);
		db.update("UPDATE users SET " + String.join(", ", setClauses) + " WHERE id = ?", values.toArray());
		User updated = userService.findUserById(user.id);
		chatService.appendAudit(updated.name, "account.profile.update", "Updated own profile", null);
		return ResponseEntity.ok(Map.of("ok", true, "user", updated));
	}

	/**
	 * Returns the authenticated user's profile, including the resolved department
	 * name.
	 *
	 * @param req the current HTTP request (for auth and CSRF checks)
	 * @return 200 OK with {@code {user: User, departmentName: String}}, or 404 if
	 *         the user record no longer exists
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/profile")
	public ResponseEntity<Map<String, Object>> getProfile(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		User fresh = userService.findUserById(user.id);
		if (fresh == null)
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		String deptName = "";
		if (fresh.departmentId != null) {
			List<Map<String, Object>> depts = db.queryForList("SELECT name FROM departments WHERE id = ?",
					fresh.departmentId);
			if (!depts.isEmpty())
				deptName = (String) depts.get(0).get("name");
		}
		return ResponseEntity.ok(Map.of("user", fresh, "departmentName", deptName));
	}

	/**
	 * Returns the authenticated user's token consumption for today and the current
	 * month.
	 *
	 * <p>
	 * Counts tokens from {@code chat_messages} rows where
	 * {@code role = 'assistant'} and the message belongs to a session owned by the
	 * current user.
	 *
	 * @param req the current HTTP request (for auth and CSRF checks)
	 * @return 200 OK with
	 *         {@code {tokensUsedToday, dailyTokenLimit, tokensUsedMonth, monthlyTokenLimit}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/usage")
	public ResponseEntity<Map<String, Object>> getUsage(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);

		// Today at midnight UTC
		String todayStart = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(ZoneId.of("UTC"))
				.toInstant().toString();
		// Current month start UTC
		String monthStartStr = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().withDayOfMonth(1)
				.atStartOfDay(ZoneId.of("UTC")).toInstant().toString();

		Integer todayTokens = db.queryForObject(
				"SELECT COALESCE(SUM(m.tokens_used),0) FROM chat_messages m "
						+ "JOIN chat_sessions s ON s.id = m.session_id "
						+ "WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?",
				Integer.class, user.id, todayStart);
		Integer monthTokens = db.queryForObject(
				"SELECT COALESCE(SUM(m.tokens_used),0) FROM chat_messages m "
						+ "JOIN chat_sessions s ON s.id = m.session_id "
						+ "WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?",
				Integer.class, user.id, monthStartStr);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("tokensUsedToday", todayTokens != null ? todayTokens : 0);
		result.put("dailyTokenLimit", user.dailyTokenLimit > 0 ? user.dailyTokenLimit : 50000);
		result.put("tokensUsedMonth", monthTokens != null ? monthTokens : 0);
		result.put("monthlyTokenLimit", user.monthlyTokenLimit > 0 ? user.monthlyTokenLimit : 1000000);
		return ResponseEntity.ok(result);
	}

	/**
	 * Converts a snake_case field name to camelCase for request body key lookup.
	 *
	 * <p>
	 * Example: {@code "avatar_initials"} → {@code "avatarInitials"}.
	 *
	 * @param s the snake_case string to convert
	 * @return the equivalent camelCase string
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private String snakeToCamel(String s) {
		StringBuilder sb = new StringBuilder();
		boolean next = false;
		for (char c : s.toCharArray()) {
			if (c == '_') {
				next = true;
			} else if (next) {
				sb.append(Character.toUpperCase(c));
				next = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
