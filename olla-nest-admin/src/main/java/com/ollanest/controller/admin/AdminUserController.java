package com.ollanest.controller.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.config.AppConfig;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Full user lifecycle management for admin users.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Admins need complete control over accounts: creating and editing users,
 * resetting passwords, computing effective access, granting/revoking permission
 * overrides, and managing live sessions (including force-logout). This
 * controller is that admin-only surface over the {@code users},
 * {@code user_overrides}, and {@code sessions} tables.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li><b>Security (LOW-7):</b> an admin cannot delete their own account, to
 * prevent accidental lockout.</li>
 * <li><b>Security (LOW-8):</b> session tokens are truncated to 8 characters in
 * the active-sessions list so valid tokens never reach the browser.</li>
 * <li><b>Validation (MED-12):</b> user updates enforce enum and bounds checks so
 * malformed input is a 400 rather than corrupt data.</li>
 * <li>Effective access is computed from role + department + overrides so the UI
 * can show exactly what a user can do.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.10 — MED-12 enum/bounds validation on user update; LOW-7/LOW-8
 * hardening</li>
 * </ul>
 *
 * <pre>
 *   GET    /api/admin/users                          — paginated user list (+search)
 *   POST   /api/admin/users                          — create a user
 *   GET    /api/admin/users/{id}                     — get a user
 *   PATCH  /api/admin/users/{id}                     — update user fields
 *   DELETE /api/admin/users/{id}                     — delete a user
 *   POST   /api/admin/users/{id}/reset-password      — reset a password
 *   GET    /api/admin/users/{id}/effective-access    — computed access
 *   POST   /api/admin/users/{id}/overrides           — add a permission override
 *   DELETE /api/admin/overrides/{id}                 — remove an override
 *   GET    /api/admin/overrides                      — list all overrides
 *   GET    /api/admin/sessions/active                — list active sessions
 *   DELETE /api/admin/sessions/user/{userId}         — force-logout a user
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController extends BaseController {

	/** Pragmatic email-format guard for new accounts (local-part@domain.tld). */
	private static final java.util.regex.Pattern EMAIL_PATTERN =
			java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	/** JDBC template for all user, session, and override DB queries. */
	private final JdbcTemplate db;

	/**
	 * Transaction template used to wrap multi-statement user update operations.
	 * Ensures partial field updates are never committed on mid-flight failures.
	 */
	private final TransactionTemplate txTemplate;

	/** Used to load and build {@link User} objects from DB rows. */
	private final UserService userService;

	/** Used to write audit log entries for user lifecycle events. */
	private final ChatService chatService;

	/** Used to invalidate sessions after password reset or role/status change. */
	private final AuthService authService;

	/** Shared JSON mapper for serialising rights lists to DB storage. */
	private final ObjectMapper mapper;

	/** Application configuration — provides the default user password from env. */
	private final AppConfig appConfig;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db          the JDBC template
	 * @param userService the user lookup and building service
	 * @param chatService the audit log helper
	 * @param authService the session management service
	 * @param mapper      the shared JSON object mapper
	 * @param appConfig   the application configuration bean
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AdminUserController(JdbcTemplate db, UserService userService, ChatService chatService,
			AuthService authService, ObjectMapper mapper, AppConfig appConfig) {
		this.db = db;
		this.txTemplate = new TransactionTemplate(new JdbcTransactionManager(db.getDataSource()));
		this.userService = userService;
		this.chatService = chatService;
		this.authService = authService;
		this.mapper = mapper;
		this.appConfig = appConfig;
	}

	/**
	 * Lists active server sessions with truncated token previews.
	 *
	 * <p>
	 * Token values are truncated to the first 8 characters to prevent exposing live
	 * session tokens to the admin UI.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {sessions: [session]}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/sessions/active")
	public ResponseEntity<Map<String, Object>> activeSessions(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		List<Map<String, Object>> rows = db
				.queryForList("SELECT s.token, s.user_id, s.expires_at, u.name, u.email, u.role "
						+ "FROM sessions s JOIN users u ON u.id = s.user_id "
						+ "WHERE s.expires_at > datetime('now') ORDER BY s.expires_at DESC");
		List<Map<String, Object>> list = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("userId", r.get("user_id"));
			item.put("name", r.get("name"));
			item.put("email", r.get("email"));
			item.put("role", r.get("role"));
			item.put("expiresAt", r.get("expires_at"));
			String token = (String) r.get("token");
			// Truncate token to prevent leakage of live session tokens
			item.put("token", token != null && token.length() > 8 ? token.substring(0, 8) + "…" : token);
			list.add(item);
		}
		return ResponseEntity.ok(Map.of("sessions", list));
	}

	/**
	 * Force-invalidates all sessions for a specific user.
	 *
	 * <p>
	 * Clears from both the in-memory cache ({@link AuthService}) and the DB.
	 *
	 * @param userId the user ID whose sessions should be terminated
	 * @param req    the current HTTP request (for admin auth check)
	 * @return 200 OK {@code {ok: true}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@DeleteMapping("/sessions/user/{userId}")
	public ResponseEntity<Map<String, Object>> clearUserSessions(@PathVariable String userId, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		// invalidateUserSessions already removes from both in-memory cache AND the DB;
		// calling db.update here again would be a redundant no-op delete — removed.
		authService.invalidateUserSessions(userId);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Returns a paginated, optionally filtered list of all users.
	 *
	 * <p>
	 * Search is performed as a case-insensitive LIKE match on {@code name} and
	 * {@code email}. Page size is capped at 100.
	 *
	 * @param req    the current HTTP request (for admin auth check)
	 * @param page   1-based page number (default 1)
	 * @param limit  results per page, max 100 (default 25)
	 * @param search optional search string matched against name and email
	 * @return 200 OK with {@code {users, total, page, limit, pages}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/users")
	public ResponseEntity<Map<String, Object>> listUsers(HttpServletRequest req,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int limit,
			@RequestParam(required = false) String search) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		page = Math.max(1, page);
		limit = Math.min(100, limit);
		int offset = (page - 1) * limit;
		String where = search != null && !search.isBlank() ? "WHERE name LIKE ? OR email LIKE ?" : "";
		String likeSearch = search != null ? "%" + search + "%" : null;

		Integer total;
		List<Map<String, Object>> users;
		String cols = "id, name, email, role, rights, department_id, active, employee_id, "
				+ "designation, team, branch, manager, organization, ai_access_tier, "
				+ "daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, "
				+ "concurrent_model_limit, api_rate_limit_per_minute, max_context_size, "
				+ "mfa_enabled, security_risk_score, access_status, access_expires_at, "
				+ "last_active_at, auth_provider, phone, avatar_initials";
		if (likeSearch != null) {
			total = db.queryForObject("SELECT COUNT(*) FROM users " + where, Integer.class, likeSearch, likeSearch);
			users = db.queryForList("SELECT " + cols + " FROM users " + where + " ORDER BY role, name LIMIT ? OFFSET ?",
					likeSearch, likeSearch, limit, offset);
		} else {
			total = db.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
			users = db.queryForList("SELECT " + cols + " FROM users ORDER BY role, name LIMIT ? OFFSET ?", limit,
					offset);
		}
		List<Object> publicUsers = new ArrayList<>();
		for (Map<String, Object> u : users)
			publicUsers.add(userService.publicUser(u));
		int totalVal = total != null ? total : 0;
		return ResponseEntity.ok(Map.of("users", publicUsers, "total", totalVal, "page", page, "limit", limit, "pages",
				(int) Math.ceil((double) totalVal / limit)));
	}

	/**
	 * Creates a new user account and returns the generated credentials.
	 *
	 * <p>
	 * If no password is provided, the default demo password is used. The BCrypt
	 * cost factor is 12. The user is automatically added to the {@code group-all}
	 * group.
	 *
	 * @param body request body with user fields; {@code name} and {@code email}
	 *             required
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok, user, credentials}}, or 400 on validation
	 *         error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/users")
	public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		String name = sanitizeText((String) body.get("name"));
		String email = sanitizeText((String) body.get("email"));
		if (name == null || email == null || name.isBlank() || email.isBlank()) {
			return ResponseEntity.status(400).body(Map.of("error", "Name and email are required"));
		}
		// BUG-042: reject malformed emails up front. An invalid address silently
		// breaks password-reset delivery and login, so fail with a 400 rather than
		// persisting an unusable account.
		if (!EMAIL_PATTERN.matcher(email).matches()) {
			return ResponseEntity.status(400).body(Map.of("error", "A valid email address is required"));
		}

		String id = uid("u");
		String password = body.get("password") != null ? body.get("password").toString()
				: appConfig.getDefaultUserPassword();
		String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
		String role = (String) body.getOrDefault("role", "user");
		String deptId = (String) body.getOrDefault("departmentId", "dept-general");
		String rights = toJson(body.getOrDefault("rights", List.of("chat:use")));

		try {
			db.update(
					"INSERT INTO users (id, name, email, password_hash, role, rights, "
							+ "department_id, active, employee_id, designation, team, branch, manager, "
							+ "organization, ai_access_tier, daily_token_limit, monthly_token_limit, "
							+ "gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, "
							+ "api_rate_limit_per_minute, max_context_size, mfa_enabled, "
							+ "security_risk_score, access_status, access_expires_at) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					id, name, email, hash, role, rights, deptId, body.getOrDefault("employeeId", ""),
					body.getOrDefault("designation", ""), body.getOrDefault("team", ""),
					body.getOrDefault("branch", ""), body.getOrDefault("manager", ""),
					body.getOrDefault("organization", "Olla Nest"), body.getOrDefault("aiAccessTier", "standard"),
					toLong(body.getOrDefault("dailyTokenLimit", 50000)),
					toLong(body.getOrDefault("monthlyTokenLimit", 1000000)),
					toLong(body.getOrDefault("gpuQuotaMinutes", 120)), toLong(body.getOrDefault("vramLimitMb", 8192)),
					toLong(body.getOrDefault("concurrentModelLimit", 1)),
					toLong(body.getOrDefault("apiRateLimitPerMinute", 30)),
					toLong(body.getOrDefault("maxContextSize", 8192)),
					Boolean.TRUE.equals(body.get("mfaEnabled")) ? 1 : 0,
					toLong(body.getOrDefault("securityRiskScore", 10)), body.getOrDefault("accessStatus", "active"),
					body.getOrDefault("accessExpiresAt", ""));
			db.update("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, 'group-all')", id);
		} catch (Exception e) {
			return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
		}
		chatService.appendAudit(admin.name, "admin.user.create", "Created user " + email, null);
		User created = userService.findUserById(id);
		return ResponseEntity.ok(Map.of("ok", true, "user", created, "credentials",
				Map.of("email", email, "password", password, "loginUrl", "/login")));
	}

	/**
	 * Returns a single user by ID.
	 *
	 * @param id  the user ID
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {user}}, or 404 if not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/users/{id}")
	public ResponseEntity<Map<String, Object>> getUser(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User user = userService.findUserById(id);
		if (user == null)
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		return ResponseEntity.ok(Map.of("user", user));
	}

	/**
	 * Updates user fields. All fields are optional; missing keys are not changed.
	 *
	 * <p>
	 * After any update, all existing sessions for the user are invalidated so the
	 * new role/access takes effect immediately. Admin accounts cannot be
	 * deactivated.
	 *
	 * @param id   the user ID to update
	 * @param body request body with any updatable user fields
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with the updated {@link User}, or 400/404 on error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PatchMapping("/users/{id}")
	public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		List<Map<String, Object>> rows = db.queryForList("SELECT id, role FROM users WHERE id = ?", id);
		if (rows.isEmpty())
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		String existingRole = (String) rows.get(0).get("role");

		Object activeVal = body.get("active");
		if (activeVal != null && !Boolean.TRUE.equals(activeVal) && "admin".equals(existingRole)) {
			return ResponseEntity.status(400).body(Map.of("error", "Admin accounts cannot be deactivated."));
		}

		// Validate role before entering the transaction
		if (body.containsKey("role")) {
			String newRole = (String) body.get("role");
			if (!Arrays.asList("admin", "user").contains(newRole)) {
				return ResponseEntity.status(400).body(Map.of("error", "Invalid role."));
			}
		}

		// Validate enum fields before writing
		if (body.containsKey("aiAccessTier")) {
			String tier = String.valueOf(body.get("aiAccessTier"));
			if (!Set.of("standard", "premium", "enterprise", "restricted").contains(tier)) {
				return ResponseEntity.status(400).body(Map.of("error", "Invalid aiAccessTier: " + tier));
			}
		}
		if (body.containsKey("accessStatus")) {
			String status = String.valueOf(body.get("accessStatus"));
			if (!Set.of("active", "suspended", "expired", "pending").contains(status)) {
				return ResponseEntity.status(400).body(Map.of("error", "Invalid accessStatus: " + status));
			}
		}
		// Numeric bounds validation
		for (String numField : List.of("dailyTokenLimit", "monthlyTokenLimit", "gpuQuotaMinutes", "vramLimitMb",
				"concurrentModelLimit", "apiRateLimitPerMinute", "maxContextSize")) {
			if (body.containsKey(numField)) {
				Object val = body.get(numField);
				if (val != null) {
					long num;
					try {
						num = Long.parseLong(val.toString());
					} catch (NumberFormatException e) {
						return ResponseEntity.status(400).body(Map.of("error", "Invalid number for " + numField));
					}
					if (num < 0 || num > 10_000_000) {
						return ResponseEntity.status(400)
								.body(Map.of("error", numField + " out of range (0–10,000,000)"));
					}
				}
			}
		}

		// Wrap all UPDATE statements in a single transaction so a mid-flight failure
		// cannot leave the user row in a partially-updated state.
		final Object activeValFinal = activeVal;
		txTemplate.executeWithoutResult(tx -> {
			if (body.containsKey("name")) {
				db.update("UPDATE users SET name = ? WHERE id = ?", sanitizeText((String) body.get("name")), id);
			}
			if (body.containsKey("email")) {
				db.update("UPDATE users SET email = ? WHERE id = ?", sanitizeText((String) body.get("email")), id);
			}
			if (body.containsKey("role")) {
				db.update("UPDATE users SET role = ? WHERE id = ?", body.get("role"), id);
			}
			if (body.containsKey("departmentId")) {
				db.update("UPDATE users SET department_id = ? WHERE id = ?", body.get("departmentId"), id);
			}
			if (activeValFinal != null) {
				db.update("UPDATE users SET active = ? WHERE id = ?", Boolean.TRUE.equals(activeValFinal) ? 1 : 0, id);
			}
			if (body.containsKey("rights") && body.get("rights") instanceof List) {
				db.update("UPDATE users SET rights = ? WHERE id = ?", toJson(body.get("rights")), id);
			}

			Map<String, String> fieldMap = new LinkedHashMap<>();
			fieldMap.put("employeeId", "employee_id");
			fieldMap.put("designation", "designation");
			fieldMap.put("team", "team");
			fieldMap.put("branch", "branch");
			fieldMap.put("manager", "manager");
			fieldMap.put("organization", "organization");
			fieldMap.put("aiAccessTier", "ai_access_tier");
			fieldMap.put("dailyTokenLimit", "daily_token_limit");
			fieldMap.put("monthlyTokenLimit", "monthly_token_limit");
			fieldMap.put("gpuQuotaMinutes", "gpu_quota_minutes");
			fieldMap.put("vramLimitMb", "vram_limit_mb");
			fieldMap.put("concurrentModelLimit", "concurrent_model_limit");
			fieldMap.put("apiRateLimitPerMinute", "api_rate_limit_per_minute");
			fieldMap.put("maxContextSize", "max_context_size");
			fieldMap.put("mfaEnabled", "mfa_enabled");
			fieldMap.put("securityRiskScore", "security_risk_score");
			fieldMap.put("accessStatus", "access_status");
			fieldMap.put("accessExpiresAt", "access_expires_at");

			for (Map.Entry<String, String> e : fieldMap.entrySet()) {
				if (!body.containsKey(e.getKey()))
					continue;
				Object val = "mfaEnabled".equals(e.getKey()) ? (Boolean.TRUE.equals(body.get(e.getKey())) ? 1 : 0)
						: body.get(e.getKey());
				db.update("UPDATE users SET " + e.getValue() + " = ? WHERE id = ?", val, id);
			}
		});

		// Invalidate sessions so new role/access takes effect immediately
		authService.invalidateUserSessions(id);
		chatService.appendAudit(admin.name, "admin.user.update", "Updated user " + id, null);
		return ResponseEntity.ok(Map.of("ok", true, "user", userService.findUserById(id)));
	}

	/**
	 * Permanently deletes a user account and all of their sessions.
	 *
	 * <p>
	 * Admins cannot delete their own account (LOW-7 fix).
	 *
	 * @param id  the user ID to delete
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK {@code {ok: true}}, or 400 if deleting own account
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: prevent admin self-deletion (LOW-7)
	 */
	@DeleteMapping("/users/{id}")
	public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		if (id.equals(admin.id)) {
			return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete your own account"));
		}
		authService.invalidateUserSessions(id);
		db.update("DELETE FROM users WHERE id = ?", id);
		chatService.appendAudit(admin.name, "admin.user.delete", "Deleted user " + id, null);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Resets a user's password to the provided value (or the default demo
	 * password).
	 *
	 * <p>
	 * Invalidates all existing sessions after the password change. Minimum password
	 * length is 12 characters.
	 *
	 * @param id   the user ID
	 * @param body request body with optional {@code password}
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK {@code {ok: true}}, or 400/404 on error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/users/{id}/reset-password")
	public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		String newPassword = body.get("password") != null ? body.get("password").toString()
				: appConfig.getDefaultUserPassword();
		if (newPassword.length() < 12) {
			return ResponseEntity.status(400).body(Map.of("error", "Password must be at least 12 characters"));
		}
		int changed = db.update("UPDATE users SET password_hash = ? WHERE id = ?",
				BCrypt.hashpw(newPassword, BCrypt.gensalt(12)), id);
		if (changed == 0)
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		authService.invalidateUserSessions(id);
		chatService.appendAudit(admin.name, "admin.user.reset_password", "Reset password for " + id, null);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Returns the computed effective access for a user, including all overrides.
	 *
	 * @param id  the user ID
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok, user, effectiveAccess, overrides}}, or 404 if
	 *         not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/users/{id}/effective-access")
	public ResponseEntity<Map<String, Object>> effectiveAccess(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User user = userService.findUserById(id);
		if (user == null)
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		return ResponseEntity.ok(Map.of("ok", true, "user", user, "effectiveAccess", userService.effectiveAccess(user),
				"overrides", userService.userOverrides(id)));
	}

	/**
	 * Adds a permission override (allow or deny) for a specific user.
	 *
	 * <p>
	 * Overrides are stored in {@code user_overrides} and applied by
	 * {@link UserService#effectiveAccess}.
	 *
	 * @param id   the user ID to add the override for
	 * @param body request body with {@code permissionKey} (required),
	 *             {@code effect} ({@code "allow"} or {@code "deny"}), optional
	 *             {@code modelId}, {@code reason}, {@code expiresAt}
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with updated effectiveAccess and overrides, or 400/404
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/users/{id}/overrides")
	public ResponseEntity<Map<String, Object>> addOverride(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		User user = userService.findUserById(id);
		if (user == null)
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		String permissionKey = body.get("permissionKey") != null ? body.get("permissionKey").toString().trim() : "";
		String effect = body.get("effect") != null ? body.get("effect").toString() : "allow";
		if (permissionKey.isBlank()) {
			return ResponseEntity.status(400).body(Map.of("error", "Permission is required"));
		}
		if (!Arrays.asList("allow", "deny").contains(effect)) {
			return ResponseEntity.status(400).body(Map.of("error", "Effect must be allow or deny"));
		}
		db.update(
				"INSERT INTO user_overrides (id, user_id, permission_key, model_id, effect, "
						+ "reason, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				uid("override"), user.id, permissionKey, body.get("modelId"), effect, body.getOrDefault("reason", ""),
				body.getOrDefault("expiresAt", ""), Instant.now().toString());
		chatService.appendAudit(admin.name, "admin.access.override",
				effect.toUpperCase() + " " + permissionKey + " for " + user.email, null);
		return ResponseEntity.ok(Map.of("ok", true, "effectiveAccess", userService.effectiveAccess(user), "overrides",
				userService.userOverrides(id)));
	}

	/**
	 * Removes a specific permission override by its ID.
	 *
	 * @param id  the override ID to delete
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK {@code {ok: true}}, or 404 if not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@DeleteMapping("/overrides/{id}")
	public ResponseEntity<Map<String, Object>> deleteOverride(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);
		int changed = db.update("DELETE FROM user_overrides WHERE id = ?", id);
		if (changed == 0) {
			return ResponseEntity.status(404).body(Map.of("error", "Override not found"));
		}
		chatService.appendAudit(admin.name, "admin.access.override.delete", "Deleted override " + id, null);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Lists all permission overrides across all users.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, overrides: [override]}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/overrides")
	public ResponseEntity<Map<String, Object>> listOverrides(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		List<Map<String, Object>> rows = db
				.queryForList("SELECT id, user_id AS userId, permission_key AS permissionKey, "
						+ "model_id AS modelId, effect, reason, expires_at AS expiresAt, "
						+ "created_at AS createdAt FROM user_overrides ORDER BY created_at DESC");
		return ResponseEntity.ok(Map.of("ok", true, "overrides", rows));
	}

	/**
	 * Placeholder for bulk override updates. Currently returns {@code ok: true}.
	 *
	 * @param body request body (not yet processed)
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK {@code {ok: true}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PatchMapping("/overrides")
	public ResponseEntity<Map<String, Object>> bulkUpdateOverrides(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Generates a unique ID with the given prefix using timestamp + random base-36.
	 *
	 * @param prefix the ID prefix (e.g. {@code "u"}, {@code "override"})
	 * @return a unique string ID
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private String uid(String prefix) {
		return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString((long) (Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
	}

	/**
	 * Serialises an object to a JSON string. Returns {@code "[]"} on error.
	 *
	 * @param obj the object to serialise (typically a List of rights)
	 * @return the JSON string representation
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "[]";
		}
	}

	/**
	 * Converts a value to a {@code long}. Supports {@link Number} and string types.
	 *
	 * @param val the value to convert
	 * @return the long value, or {@code 0} if conversion fails
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private long toLong(Object val) {
		if (val instanceof Number)
			return ((Number) val).longValue();
		try {
			return Long.parseLong(val.toString());
		} catch (Exception e) {
			return 0;
		}
	}
}
