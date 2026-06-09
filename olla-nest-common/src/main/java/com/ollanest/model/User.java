package com.ollanest.model;

import java.util.List;

/**
 * POJO representing an authenticated user loaded from the {@code users} table.
 *
 * <p>
 * Carries all enterprise access-control fields needed by the authentication,
 * routing, and quota subsystems. An instance is set as a request attribute by
 * {@code SessionAuthFilter} under the key {@code "authenticatedUser"} and
 * retrieved by {@code BaseController.getUser()}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * A dedicated, lightweight POJO avoids the overhead and coupling of pulling a
 * full JPA entity through the web layer. It also makes it straightforward to
 * serialise the user object directly to JSON for the {@code /api/state} and
 * {@code /api/auth/me} responses without accidentally including sensitive
 * fields.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>No {@code password_hash} field — this object is safe to serialise to JSON
 * and return to the browser.</li>
 * <li>Plain public fields are used instead of getters/setters to avoid Jackson
 * getter-naming surprises with the boolean {@code isEnterprise} field; the
 * field name matches the DB column exactly.</li>
 * <li>Quota and limit fields default to {@code 0}, which the quota enforcement
 * layer treats as "no configured limit" rather than "hard zero".</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.4 — added enterprise quota and access-control fields</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
public class User {

	/** UUID primary key from the {@code users} table. */
	public String id;

	/** Full display name of the user. */
	public String name;

	/** Unique email address used as the login identifier. */
	public String email;

	/** Role string: {@code "admin"} or {@code "user"}. */
	public String role;

	/**
	 * Granular permission rights parsed from the JSON {@code rights} column, e.g.
	 * {@code ["workspace:build", "model:use", "admin:users"]}.
	 */
	public List<String> rights;

	/** Foreign key to the {@code departments} table, or {@code null}. */
	public String departmentId;

	/** Whether the account is active; inactive users are denied all access. */
	public boolean active;

	/** Optional employee identifier from HR systems. */
	public String employeeId;

	/** Job designation / title. */
	public String designation;

	/** Team name within the department. */
	public String team;

	/** Branch or office location. */
	public String branch;

	/** Manager's name or identifier. */
	public String manager;

	/** Organisation name, used for multi-tenant display. */
	public String organization;

	/**
	 * AI access tier controlling which models are visible to this user, e.g.
	 * {@code "standard"}, {@code "premium"}, {@code "restricted"}.
	 */
	public String aiAccessTier;

	/** Maximum tokens this user may consume in a single calendar day. */
	public long dailyTokenLimit;

	/** Maximum tokens this user may consume in a calendar month. */
	public long monthlyTokenLimit;

	/** GPU execution quota in minutes per day. */
	public long gpuQuotaMinutes;

	/** VRAM allocation cap in megabytes for GPU model scheduling. */
	public long vramLimitMb;

	/** Maximum number of models the user may run concurrently. */
	public long concurrentModelLimit;

	/** API rate limit in requests per minute for programmatic access. */
	public long apiRateLimitPerMinute;

	/** Maximum context window size in tokens this user may request. */
	public long maxContextSize;

	/** Whether multi-factor authentication is enabled for this account. */
	public boolean mfaEnabled;

	/** Computed security risk score; higher values trigger stricter controls. */
	public long securityRiskScore;

	/**
	 * Access status: {@code "active"}, {@code "suspended"}, {@code "expired"},
	 * {@code "pending"}. Checked at login and at every auth gate.
	 */
	public String accessStatus;

	/**
	 * ISO-8601 timestamp after which login is denied, or {@code null} for permanent
	 * access. Enforced by {@code AuthController} (HIGH-2 fix).
	 */
	public String accessExpiresAt;

	/** ISO-8601 timestamp of the user's most recent authenticated request. */
	public String lastActiveAt;

	/**
	 * Authentication provider, e.g. {@code "local"}, {@code "ldap"},
	 * {@code "saml"}.
	 */
	public String authProvider;

	/** Contact phone number, optional. */
	public String phone;

	/** Two-letter initials derived from the user's name for the avatar widget. */
	public String avatarInitials;

	/** Whether this account has access to enterprise-only features. */
	public boolean isEnterprise;
}
