package com.ollanest.testinfra;

import com.ollanest.model.User;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory for building {@link User} and raw DB row fixtures used across the
 * enterprise test suite.
 *
 * <p>All constants use placeholder credentials that are safe for test code.
 * Nothing here is ever committed to a production system.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
public final class UserFactory {

	/** Stable admin user ID used across test cases. */
	public static final String ADMIN_ID   = "u-test-admin-001";
	/** Stable regular user ID used across test cases. */
	public static final String USER_ID    = "u-test-user-001";
	/** BCrypt hash for the literal string {@code "test-password-only"}. */
	public static final String BCRYPT_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVdtVBTelC";

	private UserFactory() {}

	/** Returns a fully populated admin {@link User}. */
	public static User admin() {
		User u = new User();
		u.id             = ADMIN_ID;
		u.name           = "Test Admin";
		u.email          = "junit-integration-test-only@example.com";
		u.role           = "admin";
		u.rights         = Arrays.asList("admin:full", "models:manage", "workspace:build");
		u.departmentId   = "dept-engineering";
		u.active         = true;
		u.employeeId     = "EMP-001";
		u.designation    = "System Administrator";
		u.team           = "Platform";
		u.organization   = "Olla Nest Test Org";
		u.aiAccessTier   = "premium";
		u.dailyTokenLimit        = 500_000L;
		u.monthlyTokenLimit      = 10_000_000L;
		u.gpuQuotaMinutes        = 600L;
		u.vramLimitMb            = 16384L;
		u.concurrentModelLimit   = 5L;
		u.apiRateLimitPerMinute  = 120L;
		u.maxContextSize         = 32768L;
		u.mfaEnabled             = false;
		u.securityRiskScore      = 0L;
		u.accessStatus   = "active";
		u.accessExpiresAt = "";
		u.lastActiveAt   = "";
		u.authProvider   = "local";
		u.phone          = "";
		u.avatarInitials = "TA";
		u.isEnterprise   = false;
		return u;
	}

	/** Returns a fully populated regular (non-admin) {@link User}. */
	public static User regularUser() {
		User u = new User();
		u.id             = USER_ID;
		u.name           = "Test User";
		u.email          = "test-user-seed-only@example.com";
		u.role           = "user";
		u.rights         = Arrays.asList("chat:use", "models:local:use");
		u.departmentId   = "dept-product";
		u.active         = true;
		u.employeeId     = "EMP-002";
		u.designation    = "Developer";
		u.team           = "Frontend";
		u.organization   = "Olla Nest Test Org";
		u.aiAccessTier   = "standard";
		u.dailyTokenLimit        = 50_000L;
		u.monthlyTokenLimit      = 1_000_000L;
		u.gpuQuotaMinutes        = 120L;
		u.vramLimitMb            = 8192L;
		u.concurrentModelLimit   = 1L;
		u.apiRateLimitPerMinute  = 30L;
		u.maxContextSize         = 8192L;
		u.mfaEnabled             = false;
		u.securityRiskScore      = 10L;
		u.accessStatus   = "active";
		u.accessExpiresAt = "";
		u.lastActiveAt   = "";
		u.authProvider   = "local";
		u.phone          = "+1-555-0100";
		u.avatarInitials = "TU";
		u.isEnterprise   = false;
		return u;
	}

	/** Returns a raw JDBC row map representing the admin user (includes password_hash). */
	public static Map<String, Object> adminRow() {
		Map<String, Object> row = new HashMap<>();
		row.put("id",             ADMIN_ID);
		row.put("name",           "Test Admin");
		row.put("email",          "junit-integration-test-only@example.com");
		row.put("role",           "admin");
		row.put("rights",         "[\"admin:full\",\"models:manage\"]");
		row.put("department_id",  "dept-engineering");
		row.put("active",         1);
		row.put("employee_id",    "EMP-001");
		row.put("designation",    "System Administrator");
		row.put("team",           "Platform");
		row.put("branch",         "HQ");
		row.put("manager",        "");
		row.put("organization",   "Olla Nest Test Org");
		row.put("ai_access_tier", "premium");
		row.put("daily_token_limit",        500_000L);
		row.put("monthly_token_limit",      10_000_000L);
		row.put("gpu_quota_minutes",        600L);
		row.put("vram_limit_mb",            16384L);
		row.put("concurrent_model_limit",   5L);
		row.put("api_rate_limit_per_minute",120L);
		row.put("max_context_size",         32768L);
		row.put("mfa_enabled",              0);
		row.put("security_risk_score",      0L);
		row.put("access_status",            "active");
		row.put("access_expires_at",        "");
		row.put("last_active_at",           "");
		row.put("auth_provider",            "local");
		row.put("phone",                    "");
		row.put("avatar_initials",          "TA");
		row.put("password_hash",            BCRYPT_HASH);
		return row;
	}

	/** Returns a raw JDBC row map for a regular user. */
	public static Map<String, Object> regularUserRow() {
		Map<String, Object> row = new HashMap<>();
		row.put("id",             USER_ID);
		row.put("name",           "Test User");
		row.put("email",          "test-user-seed-only@example.com");
		row.put("role",           "user");
		row.put("rights",         "[\"chat:use\",\"models:local:use\"]");
		row.put("department_id",  "dept-product");
		row.put("active",         1);
		row.put("employee_id",    "EMP-002");
		row.put("designation",    "Developer");
		row.put("team",           "Frontend");
		row.put("branch",         "HQ");
		row.put("manager",        "");
		row.put("organization",   "Olla Nest Test Org");
		row.put("ai_access_tier", "standard");
		row.put("daily_token_limit",        50_000L);
		row.put("monthly_token_limit",      1_000_000L);
		row.put("gpu_quota_minutes",        120L);
		row.put("vram_limit_mb",            8192L);
		row.put("concurrent_model_limit",   1L);
		row.put("api_rate_limit_per_minute",30L);
		row.put("max_context_size",         8192L);
		row.put("mfa_enabled",              0);
		row.put("security_risk_score",      10L);
		row.put("access_status",            "active");
		row.put("access_expires_at",        "");
		row.put("last_active_at",           "");
		row.put("auth_provider",            "local");
		row.put("phone",                    "+1-555-0100");
		row.put("avatar_initials",          "TU");
		row.put("password_hash",            BCRYPT_HASH);
		return row;
	}
}
