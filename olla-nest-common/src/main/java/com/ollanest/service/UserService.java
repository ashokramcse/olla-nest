package com.ollanest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;

/**
 * User model hydration, permission resolution, and access-control helpers.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@code UserService} is the single source of truth for translating raw
 * database rows into strongly-typed {@link User} objects, and for computing the
 * effective permission set and model access list for any given user. It
 * centralises all the logic that was previously spread across the Node.js
 * {@code src/models/user.js} module.
 *
 * <p>
 * It deliberately does <em>not</em> handle authentication (password checks,
 * session creation) — those responsibilities belong to {@code AuthService} and
 * {@code AuthController}.
 *
 * <h3>Permission resolution order</h3>
 * <ol>
 * <li>User-level rights stored in the {@code rights} JSON column</li>
 * <li>Department-level default rights (hardcoded policy per department ID)</li>
 * <li>Role-level permissions from the {@code role_catalog} table</li>
 * <li>Per-user {@code user_overrides} (allow/deny, with optional expiry)</li>
 * </ol>
 *
 * <h3>Model access resolution order</h3>
 * <ol>
 * <li>Explicit {@code access_grants} by user, department, or group</li>
 * <li>Implicit grants inferred from rights like {@code models:local:use}</li>
 * <li>Per-user {@code user_overrides} for individual model allow/deny</li>
 * </ol>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration; mirrors Node.js
 * user model logic.</li>
 * <li><b>v2026.1.4</b> — no logic changes; comprehensive JavaDoc added.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 * @see AuthService
 * @see com.ollanest.model.User
 */
@Service
public class UserService {

	/**
	 * Usernames the auth layer reserves as internal "synthetic owner" sentinels.
	 * These must never belong to a real account — creating or renaming into any of
	 * them would grant silent privilege escalation (internal-tool is treated as
	 * admin by require_admin, api is the bearer-token owner sentinel, etc.).
	 */
	public static final Set<String> RESERVED_USERNAMES = Set.of("internal-tool", "api", "demo", "system", "admin");

	/** JDBC template for all database queries in this service. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper used to deserialise the {@code rights} JSON column. */
	private final ObjectMapper mapper;

	public UserService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/** Guard: throw IllegalArgumentException if the username is reserved. */
	public static void assertNotReserved(String username) {
		if (username != null && RESERVED_USERNAMES.contains(username.toLowerCase())) {
			throw new IllegalArgumentException("Username '" + username + "' is reserved and cannot be used");
		}
	}

	// -------------------------------------------------------------------------
	// User hydration
	// -------------------------------------------------------------------------

	/**
	 * Hydrates a {@link User} object from a raw JDBC result-set row map.
	 *
	 * <p>
	 * Reads all columns that the {@code users} table may return and maps them to
	 * the corresponding {@link User} fields, applying defaults wherever a column is
	 * absent or {@code null}. Handles both snake_case DB column names and camelCase
	 * aliases (via the {@code firstStr} / {@code strOr} helpers) so the same method
	 * works for rows from raw queries and rows from DTO projections.
	 *
	 * <p>
	 * Notable logic:
	 * <ul>
	 * <li>{@code rights} is stored as a JSON array string and parsed into a
	 * {@code List<String>}.</li>
	 * <li>{@code isEnterprise} is derived: any auth provider other than
	 * {@code "local"} is enterprise.</li>
	 * <li>Password hash is intentionally never mapped — the returned object is safe
	 * to serialise.</li>
	 * </ul>
	 *
	 * @param row a {@code Map<String, Object>} from
	 *            {@link JdbcTemplate#queryForList}; returns {@code null} if
	 *            {@code row} itself is {@code null}
	 * @return a fully hydrated {@link User}, or {@code null} if {@code row} is
	 *         {@code null}
	 * @since v2026.1.0
	 */
	public User publicUser(Map<String, Object> row) {
		if (row == null)
			return null;
		User u = new User();
		u.id = str(row, "id");
		u.name = str(row, "name");
		u.email = str(row, "email");
		u.role = str(row, "role");
		u.rights = safeJsonList(str(row, "rights"));
		u.departmentId = firstStr(row, "department_id", "departmentId");
		u.active = boolVal(row, "active");
		u.employeeId = strOr(row, "", "employee_id", "employeeId");
		u.designation = strOr(row, "", "designation");
		u.team = strOr(row, "", "team");
		u.branch = strOr(row, "", "branch");
		u.manager = strOr(row, "", "manager");
		u.organization = strOr(row, "Olla Nest", "organization");
		u.aiAccessTier = strOr(row, "standard", "ai_access_tier", "aiAccessTier");
		u.dailyTokenLimit = longVal(row, 50000, "daily_token_limit", "dailyTokenLimit");
		u.monthlyTokenLimit = longVal(row, 1000000, "monthly_token_limit", "monthlyTokenLimit");
		u.gpuQuotaMinutes = longVal(row, 120, "gpu_quota_minutes", "gpuQuotaMinutes");
		u.vramLimitMb = longVal(row, 8192, "vram_limit_mb", "vramLimitMb");
		u.concurrentModelLimit = longVal(row, 1, "concurrent_model_limit", "concurrentModelLimit");
		u.apiRateLimitPerMinute = longVal(row, 30, "api_rate_limit_per_minute", "apiRateLimitPerMinute");
		u.maxContextSize = longVal(row, 8192, "max_context_size", "maxContextSize");
		u.mfaEnabled = boolVal(row, "mfa_enabled", "mfaEnabled");
		u.securityRiskScore = longVal(row, 10, "security_risk_score", "securityRiskScore");
		u.accessStatus = strOr(row, u.active ? "active" : "suspended", "access_status", "accessStatus");
		u.accessExpiresAt = strOr(row, "", "access_expires_at", "accessExpiresAt");
		u.lastActiveAt = strOr(row, "", "last_active_at", "lastActiveAt");
		u.authProvider = strOr(row, "local", "auth_provider", "authProvider");
		u.phone = strOr(row, "", "phone");
		u.avatarInitials = strOr(row, "", "avatar_initials", "avatarInitials");
		u.isEnterprise = !"local".equals(u.authProvider);
		return u;
	}

	/**
	 * Looks up and hydrates a {@link User} by their primary key.
	 *
	 * <p>
	 * Queries all non-sensitive columns from the {@code users} table (excluding
	 * {@code password_hash}) and delegates to {@link #publicUser(Map)} for
	 * hydration.
	 *
	 * @param userId the primary key of the user (e.g. {@code "u-admin"}); must not
	 *               be {@code null}
	 * @return a hydrated {@link User}, or {@code null} if no user with that ID
	 *         exists
	 * @since v2026.1.0
	 */
	public User findUserById(String userId) {
		List<Map<String, Object>> rows = db
				.queryForList(
						"SELECT id, name, email, role, rights, department_id, active, employee_id, "
								+ "designation, team, branch, manager, organization, ai_access_tier, "
								+ "daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, "
								+ "concurrent_model_limit, api_rate_limit_per_minute, max_context_size, "
								+ "mfa_enabled, security_risk_score, access_status, access_expires_at, "
								+ "last_active_at, auth_provider, phone, avatar_initials " + "FROM users WHERE id = ?",
						userId);
		if (rows.isEmpty())
			return null;
		return publicUser(rows.get(0));
	}

	/**
	 * Looks up and hydrates an active {@link User} by their email address.
	 *
	 * <p>
	 * Only returns users with {@code active = 1}. Used by {@code AuthController}
	 * during login and by {@code SsoController} during SSO callback user
	 * resolution.
	 *
	 * @param email the email address to search for; case-sensitive as stored in DB
	 * @return a hydrated {@link User}, or {@code null} if no active user has that
	 *         email
	 * @since v2026.1.0
	 */
	public User findUserByEmail(String email) {
		List<Map<String, Object>> rows = db
				.queryForList("SELECT id, name, email, role, rights, department_id, active, employee_id, "
						+ "designation, team, branch, manager, organization, ai_access_tier, "
						+ "daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, "
						+ "concurrent_model_limit, api_rate_limit_per_minute, max_context_size, "
						+ "mfa_enabled, security_risk_score, access_status, access_expires_at, "
						+ "last_active_at, auth_provider, phone, avatar_initials "
						+ "FROM users WHERE email = ? AND active = 1", email);
		if (rows.isEmpty())
			return null;
		return publicUser(rows.get(0));
	}

	/**
	 * Returns the raw database row for a user, including the {@code password_hash}
	 * column.
	 *
	 * <p>
	 * <b>Security:</b> this method returns the BCrypt password hash and must never
	 * be serialised to JSON or returned to the browser. It is used exclusively by
	 * {@code AuthController} for login verification and by
	 * {@code AccountController} for password-change operations.
	 *
	 * @param userId the primary key of the user; must not be {@code null}
	 * @return the raw result-set row including {@code password_hash}, or
	 *         {@code null} if no user exists with that ID
	 * @since v2026.1.0
	 */
	public Map<String, Object> findRawUserById(String userId) {
		List<Map<String, Object>> rows = db.queryForList("SELECT *, password_hash FROM users WHERE id = ?", userId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// -------------------------------------------------------------------------
	// Permission helpers
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} if the user holds a specific permission right.
	 *
	 * <p>
	 * Admins implicitly hold all rights regardless of what is in their
	 * {@code rights} column, which is the same rule applied everywhere in the
	 * codebase.
	 *
	 * @param user  the authenticated user; must not be {@code null}
	 * @param right the permission key to check, e.g. {@code "workspace:build"}
	 * @return {@code true} if the user is an admin or explicitly holds the right
	 * @since v2026.1.0
	 */
	public boolean hasRight(User user, String right) {
		return "admin".equals(user.role) || (user.rights != null && user.rights.contains(right));
	}

	/**
	 * Computes the full list of model IDs that the user is allowed to run.
	 *
	 * <p>
	 * Resolution order (union semantics — any grant is sufficient):
	 * <ol>
	 * <li>Explicit {@code access_grants} rows for this user</li>
	 * <li>Explicit {@code access_grants} rows for the user's department</li>
	 * <li>Explicit {@code access_grants} rows for any group the user belongs
	 * to</li>
	 * <li>Implicit grants from effective rights ({@code models:local:use},
	 * {@code models:external:use}, {@code models:manage})</li>
	 * <li>Per-user {@code user_overrides} (allow or deny individual models)</li>
	 * </ol>
	 * <p>
	 * Admin users bypass all access-grant logic and always see every
	 * available/configured model.
	 *
	 * @param user the authenticated user; must not be {@code null}
	 * @return a mutable {@code List<String>} of model IDs the user may access;
	 *         never {@code null}, may be empty
	 * @since v2026.1.0
	 */
	public List<String> allowedModelIds(User user) {
		if ("admin".equals(user.role)) {
			return db.queryForList("SELECT id FROM models WHERE status IN ('available','configured')", String.class);
		}
		List<String> groupIds = db.queryForList("SELECT group_id FROM user_groups WHERE user_id = ?", String.class,
				user.id);
		Set<String> ids = new HashSet<>();

		// Explicit access_grants: by user
		ids.addAll(db.queryForList("SELECT model_id FROM access_grants "
				+ "WHERE subject_type = 'user' AND subject_id = ? AND can_use = 1", String.class, user.id));

		// Explicit access_grants: by department
		if (user.departmentId != null) {
			ids.addAll(db.queryForList(
					"SELECT model_id FROM access_grants "
							+ "WHERE subject_type = 'department' AND subject_id = ? AND can_use = 1",
					String.class, user.departmentId));
		}

		// Explicit access_grants: by group membership — single IN query, not N+1
		if (!groupIds.isEmpty()) {
			String placeholders = groupIds.stream().map(g -> "?").collect(Collectors.joining(","));
			Object[] params = groupIds.toArray();
			ids.addAll(db.queryForList("SELECT model_id FROM access_grants WHERE subject_type = 'group' "
					+ "AND subject_id IN (" + placeholders + ") AND can_use = 1", String.class, params));
		}

		// Implicit grants from effective rights
		Set<String> effectiveRights = new HashSet<>();
		if (user.rights != null)
			effectiveRights.addAll(user.rights);
		effectiveRights.addAll(departmentDefaults(user.departmentId));

		if (effectiveRights.contains("models:local:use") || effectiveRights.contains("models:manage")
				|| effectiveRights.contains("models:external:use")) {
			ids.addAll(db.queryForList("SELECT id FROM models WHERE provider != 'api' AND status != 'disabled'",
					String.class));
		}
		if (effectiveRights.contains("models:external:use") || effectiveRights.contains("api:use")) {
			ids.addAll(db.queryForList("SELECT id FROM models WHERE provider = 'api' AND status != 'disabled'",
					String.class));
		}

		// Per-user overrides (allow/deny with optional expiry)
		List<Map<String, Object>> overrides = db.queryForList("SELECT permission_key, model_id, effect, expires_at "
				+ "FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC", user.id);
		for (Map<String, Object> ov : overrides) {
			String modelId = (String) ov.get("model_id");
			if (modelId == null)
				continue;
			String expiresAt = (String) ov.get("expires_at");
			if (expiresAt != null && !expiresAt.isBlank()) {
				try {
					if (System.currentTimeMillis() > Instant.parse(expiresAt).toEpochMilli())
						continue;
				} catch (Exception ignored) {
				}
			}
			String effect = (String) ov.get("effect");
			if ("allow".equals(effect))
				ids.add(modelId);
			if ("deny".equals(effect))
				ids.remove(modelId);
		}
		return new ArrayList<>(ids);
	}

	/**
	 * Returns the default permission rights for a given department ID.
	 *
	 * <p>
	 * These are hardcoded policy defaults applied when a user has no explicit
	 * rights assigned. They represent the minimum access level for each department:
	 * <ul>
	 * <li>{@code dept-product} — chat, local models, coding models, workspace
	 * build, file upload</li>
	 * <li>{@code dept-support} — chat, local models, reasoning models, file
	 * upload</li>
	 * <li>(all others) — chat and local models only</li>
	 * </ul>
	 *
	 * @param departmentId the department ID to look up; may be {@code null}
	 * @return an immutable list of default right strings for the department; never
	 *         {@code null}
	 * @since v2026.1.0
	 */
	public List<String> departmentDefaults(String departmentId) {
		if ("dept-product".equals(departmentId)) {
			return Arrays.asList("chat:use", "models:local:use", "models:coding:use", "workspace:build",
					"files:upload");
		}
		if ("dept-support".equals(departmentId)) {
			return Arrays.asList("chat:use", "models:local:use", "models:reasoning:use", "files:upload");
		}
		return Arrays.asList("chat:use", "models:local:use");
	}

	/**
	 * Computes the full effective access summary for a user, including permissions,
	 * denied rights, allowed models, groups, and quota information.
	 *
	 * <p>
	 * Merges rights from all sources (user, department, role, overrides) and
	 * returns a structured map suitable for the {@code GET /api/account/access}
	 * endpoint. The {@code denied} list shows rights that were explicitly revoked
	 * via an active override, useful for diagnosing unexpected access restrictions.
	 *
	 * @param user the authenticated user; must not be {@code null}
	 * @return a {@code Map<String, Object>} with keys: {@code permissions} (sorted
	 *         {@code List<String>}), {@code denied} (sorted {@code List<String>}),
	 *         {@code allowedModelIds} ({@code List<String>}), {@code groups}
	 *         ({@code List<String>}), {@code sourcePriority}
	 *         ({@code List<String>}), {@code quotas} ({@code Map<String, Object>}
	 *         with all quota fields)
	 * @since v2026.1.0
	 */
	public Map<String, Object> effectiveAccess(User user) {
		List<String> rolePerms = new ArrayList<>();
		List<Map<String, Object>> roleCatalogRow = db.queryForList("SELECT permissions FROM role_catalog WHERE id = ?",
				user.role);
		if (!roleCatalogRow.isEmpty()) {
			rolePerms = safeJsonList((String) roleCatalogRow.get(0).get("permissions"));
		}

		Set<String> permissions = new LinkedHashSet<>();
		if (user.rights != null)
			permissions.addAll(user.rights);
		permissions.addAll(departmentDefaults(user.departmentId));
		permissions.addAll(rolePerms);

		Set<String> denied = new LinkedHashSet<>();
		List<Map<String, Object>> overrides = db
				.queryForList("SELECT permission_key, effect, expires_at FROM user_overrides "
						+ "WHERE user_id = ? ORDER BY created_at DESC", user.id);
		for (Map<String, Object> ov : overrides) {
			String expiresAt = (String) ov.get("expires_at");
			if (expiresAt != null && !expiresAt.isBlank()) {
				try {
					if (System.currentTimeMillis() > Instant.parse(expiresAt).toEpochMilli())
						continue;
				} catch (Exception ignored) {
				}
			}
			String effect = (String) ov.get("effect");
			String key = (String) ov.get("permission_key");
			if ("deny".equals(effect))
				denied.add(key);
			if ("allow".equals(effect))
				permissions.add(key);
		}
		denied.forEach(permissions::remove);

		List<String> groups = db.queryForList("SELECT group_id FROM user_groups WHERE user_id = ?", String.class,
				user.id);

		List<String> sortedPerms = new ArrayList<>(permissions);
		Collections.sort(sortedPerms);
		List<String> sortedDenied = new ArrayList<>(denied);
		Collections.sort(sortedDenied);

		Map<String, Object> quotas = new LinkedHashMap<>();
		quotas.put("dailyTokenLimit", user.dailyTokenLimit);
		quotas.put("monthlyTokenLimit", user.monthlyTokenLimit);
		quotas.put("gpuQuotaMinutes", user.gpuQuotaMinutes);
		quotas.put("vramLimitMb", user.vramLimitMb);
		quotas.put("concurrentModelLimit", user.concurrentModelLimit);
		quotas.put("apiRateLimitPerMinute", user.apiRateLimitPerMinute);
		quotas.put("maxContextSize", user.maxContextSize);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("permissions", sortedPerms);
		result.put("denied", sortedDenied);
		result.put("allowedModelIds", allowedModelIds(user));
		result.put("groups", groups);
		result.put("sourcePriority",
				Arrays.asList("user override", "department policy", "role permission", "organization default"));
		result.put("quotas", quotas);
		return result;
	}

	/**
	 * Returns all active permission overrides for a specific user.
	 *
	 * <p>
	 * Overrides are returned in descending creation order so the most recent entry
	 * for each permission key takes precedence visually. Note: expiry filtering is
	 * <em>not</em> applied here — expired overrides are returned so the admin UI
	 * can display their history; runtime permission computation in
	 * {@link #effectiveAccess} skips expired overrides.
	 *
	 * @param userId the user's primary key; must not be {@code null}
	 * @return a list of override rows with camelCase-aliased columns: {@code id},
	 *         {@code userId}, {@code permissionKey}, {@code modelId},
	 *         {@code effect}, {@code reason}, {@code expiresAt}, {@code createdAt}
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> userOverrides(String userId) {
		return db.queryForList("SELECT id, user_id AS userId, permission_key AS permissionKey, "
				+ "model_id AS modelId, effect, reason, " + "expires_at AS expiresAt, created_at AS createdAt "
				+ "FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC", userId);
	}

	/**
	 * Returns all role definitions from the {@code role_catalog} table.
	 *
	 * <p>
	 * The {@code permissions} column is deserialised from its JSON array string
	 * into a {@code List<String>}. The {@code systemRole} column is converted from
	 * its integer representation to a proper {@code boolean}.
	 *
	 * @return a list of role maps with keys: {@code id}, {@code name},
	 *         {@code description}, {@code permissions} ({@code List<String>}),
	 *         {@code systemRole} ({@code boolean}); ordered alphabetically by name
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> roleCatalog() {
		List<Map<String, Object>> rows = db.queryForList("SELECT id, name, description, permissions, "
				+ "system_role AS systemRole FROM role_catalog ORDER BY name");
		for (Map<String, Object> r : rows) {
			r.put("permissions", safeJsonList((String) r.get("permissions")));
			Object sr = r.get("systemRole");
			r.put("systemRole", sr != null && ((Number) sr).intValue() != 0);
		}
		return rows;
	}

	/**
	 * Returns all permission definitions from the {@code permission_catalog} table.
	 *
	 * <p>
	 * The catalog drives the permission picker UI in the admin panel, showing all
	 * supported permission keys with their category, description, and risk level.
	 *
	 * @return a list of permission maps with keys: {@code key}, {@code category},
	 *         {@code description}, {@code riskLevel}; ordered by category then key
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> permissionCatalog() {
		return db.queryForList("SELECT key, category, description, risk_level AS riskLevel "
				+ "FROM permission_catalog ORDER BY category, key");
	}

	// -------------------------------------------------------------------------
	// Private JSON / row-mapping helpers
	// -------------------------------------------------------------------------

	/**
	 * Safely parses a JSON array string into a {@code List<String>}.
	 *
	 * <p>
	 * Returns an empty list (never {@code null}) if the input is null, blank, or
	 * not valid JSON. This prevents NPEs when the {@code rights} column is missing
	 * or has been set to a legacy non-JSON value.
	 *
	 * @param json the JSON array string (e.g.
	 *             {@code ["chat:use","models:manage"]}); may be {@code null} or
	 *             blank
	 * @return the parsed list, or an empty mutable list on any parse failure
	 * @since v2026.1.0
	 */
	public List<String> safeJsonList(String json) {
		try {
			if (json == null || json.isBlank())
				return new ArrayList<>();
			return mapper.readValue(json, new TypeReference<List<String>>() {
			});
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	/**
	 * Returns the string value for a single column key, or {@code null} if absent.
	 *
	 * @param row the JDBC result-set row map
	 * @param key the column name to look up
	 * @return the string value, or {@code null}
	 * @since v2026.1.0
	 */
	private String str(Map<String, Object> row, String key) {
		Object v = row.get(key);
		return v != null ? v.toString() : null;
	}

	/**
	 * Returns the first non-null string value found among the given column name
	 * candidates.
	 *
	 * <p>
	 * Used to handle both snake_case column names and camelCase aliases in the same
	 * row.
	 *
	 * @param row  the JDBC result-set row map
	 * @param keys one or more column name candidates to try in order
	 * @return the first non-null string value found, or {@code null} if all are
	 *         absent
	 * @since v2026.1.0
	 */
	private String firstStr(Map<String, Object> row, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null)
				return v.toString();
		}
		return null;
	}

	/**
	 * Returns the first non-null string value found among the given column name
	 * candidates, falling back to a default value if none are present.
	 *
	 * @param row  the JDBC result-set row map
	 * @param def  the default value to return if no key has a non-null value
	 * @param keys one or more column name candidates to try in order
	 * @return the first non-null string value, or {@code def}
	 * @since v2026.1.0
	 */
	private String strOr(Map<String, Object> row, String def, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null)
				return v.toString();
		}
		return def;
	}

	/**
	 * Reads a boolean value from the row, accepting {@code Boolean},
	 * {@code Number}, or string {@code "1"}/{@code "true"} representations.
	 *
	 * <p>
	 * SQLite stores booleans as integers (0/1); this helper bridges that to a Java
	 * {@code boolean} without requiring a type cast at every call site.
	 *
	 * @param row  the JDBC result-set row map
	 * @param keys one or more column name candidates to try in order
	 * @return {@code true} if the first found value is truthy; {@code false} if all
	 *         absent
	 * @since v2026.1.0
	 */
	private boolean boolVal(Map<String, Object> row, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null) {
				if (v instanceof Boolean)
					return (Boolean) v;
				if (v instanceof Number)
					return ((Number) v).intValue() != 0;
				return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
			}
		}
		return false;
	}

	/**
	 * Reads a {@code long} value from the row, returning a default if the column is
	 * absent.
	 *
	 * @param row  the JDBC result-set row map
	 * @param def  the default value to use if no key has a non-null value
	 * @param keys one or more column name candidates to try in order
	 * @return the numeric value as {@code long}, or {@code def}
	 * @since v2026.1.0
	 */
	private long longVal(Map<String, Object> row, long def, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null) {
				try {
					return ((Number) v).longValue();
				} catch (Exception ignored) {
				}
			}
		}
		return def;
	}
}
