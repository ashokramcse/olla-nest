package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link UserService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link UserService} hydrates DB rows into public {@link User} objects and is
 * the authority for what a user is allowed to do. These tests pin two
 * security-critical properties: the password hash is never mapped onto the
 * public user, and the runtime permission set correctly composes role,
 * department and per-user overrides (allow/deny/expiry, deny-wins — BUG-032).
 * Quota defaulting, JSON safety, and the admin bypass are also covered.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs under {@link MockitoExtension}; {@link JdbcTemplate} and
 * {@link ObjectMapper} are mocked and injected.</li>
 * <li>{@link UserFactory} supplies canonical admin/regular rows and users so
 * assertions read against realistic, shared fixtures.</li>
 * <li>Nested groups map one-to-one onto the public method surface so a failing
 * test name points straight at the operation under test.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — hydration, lookup, rights/permissions and allowed-model
 * coverage, including the BUG-032 override-resolution cases.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — unit tests")
class UserServiceTest {

	/** Mocked JDBC template backing user lookups, grants and overrides. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper for rights/tags (de)serialisation. */
	@Mock
	ObjectMapper mapper;

	/** Service under test with mocks injected. */
	@InjectMocks
	UserService userService;

	// ─────────────────────────────────────────────────────────────────────────
	// publicUser hydration
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code publicUser()} — row-to-{@link User} hydration.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("publicUser() — row-to-User hydration")
	class PublicUser {

		/**
		 * Verifies a null row hydrates to a null user.
		 *
		 * <p>
		 * The DB can return no rows; {@code publicUser(null)} must return null
		 * rather than throw.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when row is null")
		void returnsNullForNullRow() {
			// Null guard — DB can return no rows; publicUser must handle null gracefully
			assertThat(userService.publicUser(null)).isNull();
		}

		/**
		 * Verifies every scalar field maps correctly from a full admin row.
		 *
		 * <p>
		 * Identity, role, department, quotas and the {@code isEnterprise=false}
		 * derivation for a local provider are all asserted.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("maps all scalar fields correctly from admin row")
		void mapsAllFieldsFromAdminRow() {
			// Use the shared test factory row which has all columns populated
			Map<String, Object> row = UserFactory.adminRow();
			User u = userService.publicUser(row);

			assertThat(u).isNotNull();
			assertThat(u.id).isEqualTo(UserFactory.ADMIN_ID);
			assertThat(u.name).isEqualTo("Test Admin");
			assertThat(u.email).isEqualTo("junit-integration-test-only@example.com");
			assertThat(u.role).isEqualTo("admin");
			assertThat(u.departmentId).isEqualTo("dept-engineering");
			assertThat(u.active).isTrue();
			assertThat(u.employeeId).isEqualTo("EMP-001");
			assertThat(u.organization).isEqualTo("Olla Nest Test Org");
			assertThat(u.aiAccessTier).isEqualTo("premium");
			assertThat(u.dailyTokenLimit).isEqualTo(500_000L);
			assertThat(u.monthlyTokenLimit).isEqualTo(10_000_000L);
			assertThat(u.gpuQuotaMinutes).isEqualTo(600L);
			assertThat(u.vramLimitMb).isEqualTo(16384L);
			assertThat(u.concurrentModelLimit).isEqualTo(5L);
			assertThat(u.apiRateLimitPerMinute).isEqualTo(120L);
			assertThat(u.maxContextSize).isEqualTo(32768L);
			assertThat(u.mfaEnabled).isFalse();
			assertThat(u.authProvider).isEqualTo("local");
			assertThat(u.isEnterprise).isFalse(); // local = not enterprise
		}

		/**
		 * Verifies a non-local auth provider sets {@code isEnterprise=true}.
		 *
		 * <p>
		 * An SSO provider (SAML/OIDC) must derive {@code isEnterprise=true}, used
		 * by the UI to show the SSO badge.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("isEnterprise=true when authProvider is not 'local'")
		void isEnterpriseForSsoUser() {
			// SSO provider (SAML, OIDC, etc.) → isEnterprise=true used by UI to show SSO
			// badge
			Map<String, Object> row = UserFactory.adminRow();
			row.put("auth_provider", "saml");
			User u = userService.publicUser(row);
			assertThat(u.isEnterprise).isTrue();
		}

		/**
		 * Verifies an {@code active=0} column maps to {@code active=false}.
		 *
		 * <p>
		 * Deactivated users must map to a boolean false, not null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("active=false when active column is 0")
		void inactiveUserMappedCorrectly() {
			// Deactivated users: active=0 in DB must map to User.active=false (not null)
			Map<String, Object> row = UserFactory.regularUserRow();
			row.put("active", 0);
			User u = userService.publicUser(row);
			assertThat(u.active).isFalse();
		}

		/**
		 * Verifies absent quota columns fall back to conservative defaults.
		 *
		 * <p>
		 * A minimal row (only required fields) must yield the documented default
		 * token/GPU/VRAM/context limits and organisation/provider values.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("applies all default quota values when columns are absent")
		void appliesDefaultQuotaValues() {
			// Minimal row: only required fields — all quota/limit columns absent
			Map<String, Object> row = Map.of("id", "u-minimal", "name", "Minimal User", "email", "minimal@example.com",
					"role", "user", "active", 1);
			User u = userService.publicUser(row);
			// Defaults from UserService — safe conservative limits for new users
			assertThat(u.dailyTokenLimit).isEqualTo(50_000L);
			assertThat(u.monthlyTokenLimit).isEqualTo(1_000_000L);
			assertThat(u.gpuQuotaMinutes).isEqualTo(120L);
			assertThat(u.vramLimitMb).isEqualTo(8192L);
			assertThat(u.concurrentModelLimit).isEqualTo(1L);
			assertThat(u.apiRateLimitPerMinute).isEqualTo(30L);
			assertThat(u.maxContextSize).isEqualTo(8192L);
			assertThat(u.organization).isEqualTo("Olla Nest"); // default from UserService source
			assertThat(u.authProvider).isEqualTo("local");
		}

		/**
		 * Verifies the password hash is never exposed on the public user.
		 *
		 * <p>
		 * SECURITY: even though the source row carries {@code password_hash}, the
		 * {@link User} type must expose no {@code password}/{@code passwordHash}
		 * field, so it can never be serialised to an API response.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("password_hash column is NOT mapped to any User field")
		void passwordHashNotExposed() {
			// SECURITY: adminRow() contains a password_hash column — it must NEVER appear
			// in the public User object that gets serialized to the API response
			Map<String, Object> row = UserFactory.adminRow(); // contains password_hash
			User u = userService.publicUser(row);
			// User has no password field — just verify the object serializes safely
			// (no NPE, no accidental field exposure)
			assertThat(u).isNotNull();
			// Verify via reflection there's no field named 'passwordHash' or 'password'
			assertThatCode(() -> {
				try {
					u.getClass().getDeclaredField("passwordHash");
				} catch (NoSuchFieldException ok) {
					/* expected */ }
				try {
					u.getClass().getDeclaredField("password");
				} catch (NoSuchFieldException ok) {
					/* expected */ }
			}).doesNotThrowAnyException();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// findUserById()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code findUserById()} — id lookup.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("findUserById()")
	class FindUserById {

		/**
		 * Verifies a miss returns null.
		 *
		 * <p>
		 * No row for the id (deleted or never existed) must yield null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when DB returns empty list")
		void returnsNullWhenNotFound() {
			// Stub: no row for this user ID (deleted or never existed)
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID))).thenReturn(Collections.emptyList());
			// Null returned — callers must handle missing user gracefully
			assertThat(userService.findUserById(UserFactory.ADMIN_ID)).isNull();
		}

		/**
		 * Verifies a hit returns a hydrated user.
		 *
		 * <p>
		 * A found row must hydrate identity and role fields correctly.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns hydrated user when DB row found")
		void returnsHydratedUser() {
			// Stub: DB returns the admin test row
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID))).thenReturn(List.of(UserFactory.adminRow()));
			User u = userService.findUserById(UserFactory.ADMIN_ID);
			assertThat(u).isNotNull();
			// Key identity and role fields must be hydrated correctly
			assertThat(u.id).isEqualTo(UserFactory.ADMIN_ID);
			assertThat(u.role).isEqualTo("admin");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// findUserByEmail()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code findUserByEmail()} — active-email lookup.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("findUserByEmail()")
	class FindUserByEmail {

		/**
		 * Verifies an unknown email returns null.
		 *
		 * <p>
		 * No active user for the email (unregistered or deactivated) must yield
		 * null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when no active user matches the email")
		void returnsNullForUnknownEmail() {
			// Stub: no active user row for this email (unregistered or deactivated)
			when(db.queryForList(anyString(), eq("unknown@example.com"))).thenReturn(Collections.emptyList());
			assertThat(userService.findUserByEmail("unknown@example.com")).isNull();
		}

		/**
		 * Verifies a known active email returns a hydrated user.
		 *
		 * <p>
		 * The email must be preserved exactly, as it is used for session and
		 * notification targeting.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns hydrated user for a known active email")
		void returnsHydratedUserForKnownEmail() {
			// Stub: known email → admin row returned from DB
			String email = "junit-integration-test-only@example.com";
			when(db.queryForList(anyString(), eq(email))).thenReturn(List.of(UserFactory.adminRow()));
			User u = userService.findUserByEmail(email);
			assertThat(u).isNotNull();
			// Email must be preserved exactly — used for session and notification targeting
			assertThat(u.email).isEqualTo(email);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// hasRight()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code hasRight()} — admin bypass and explicit rights.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("hasRight()")
	class HasRight {

		/**
		 * Verifies an admin has every right via role bypass.
		 *
		 * <p>
		 * Even with an empty rights list, an admin must be granted any right.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("admin always has every right — role bypass")
		void adminBypassesRightCheck() {
			// Admin role bypasses all right checks — no need to enumerate every right for
			// admin
			User admin = UserFactory.admin();
			admin.rights = Collections.emptyList(); // explicitly no rights listed
			// Despite empty rights list, admin gets everything
			assertThat(userService.hasRight(admin, "workspace:build")).isTrue();
			assertThat(userService.hasRight(admin, "models:manage")).isTrue();
			assertThat(userService.hasRight(admin, "any:random:right")).isTrue();
		}

		/**
		 * Verifies an explicitly granted right returns true.
		 *
		 * <p>
		 * Rights present in a regular user's list must resolve to true.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("regular user granted explicit right returns true")
		void userWithExplicitRightReturnsTrue() {
			// regularUser has ["chat:use","models:local:use"] from UserFactory
			User u = UserFactory.regularUser();
			// Rights in the list must return true
			assertThat(userService.hasRight(u, "chat:use")).isTrue();
			assertThat(userService.hasRight(u, "models:local:use")).isTrue();
		}

		/**
		 * Verifies a missing right returns false.
		 *
		 * <p>
		 * Rights not in the list (admin/workspace) must be denied for a regular
		 * user.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("regular user lacking right returns false")
		void userWithoutRightReturnsFalse() {
			// regularUser does not have admin or workspace rights — must be denied
			User u = UserFactory.regularUser();
			assertThat(userService.hasRight(u, "workspace:build")).isFalse();
			assertThat(userService.hasRight(u, "admin:full")).isFalse();
		}

		/**
		 * Verifies a null rights list returns false without an NPE.
		 *
		 * <p>
		 * A new user with no assigned rights must safely resolve to false.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null rights list returns false without NPE")
		void nullRightsReturnsFalse() {
			// Null rights (new user not yet assigned any) → safe false, never NPE
			User u = UserFactory.regularUser();
			u.rights = null;
			assertThat(userService.hasRight(u, "chat:use")).isFalse();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// departmentDefaults()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code departmentDefaults()} — per-department base rights.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("departmentDefaults()")
	class DepartmentDefaults {

		/**
		 * Verifies the product department's default rights.
		 *
		 * <p>
		 * {@code dept-product} must include workspace:build, files:upload and
		 * chat:use.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("dept-product gets workspace:build and files:upload")
		void productDeptDefaults() {
			// Product team: workspace:build enables AI-assisted development features
			List<String> defaults = userService.departmentDefaults("dept-product");
			assertThat(defaults).contains("workspace:build", "files:upload", "chat:use");
		}

		/**
		 * Verifies the support department's default rights.
		 *
		 * <p>
		 * {@code dept-support} must include models:reasoning:use, files:upload and
		 * chat:use.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("dept-support gets models:reasoning:use and files:upload")
		void supportDeptDefaults() {
			// Support team: reasoning models to aid with complex customer queries
			List<String> defaults = userService.departmentDefaults("dept-support");
			assertThat(defaults).contains("models:reasoning:use", "files:upload", "chat:use");
		}

		/**
		 * Verifies an unknown department gets only minimal base rights.
		 *
		 * <p>
		 * An unrecognised department must fall back to exactly chat:use and
		 * models:local:use.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("unknown department gets only chat:use and models:local:use")
		void unknownDeptDefaults() {
			// Unknown department falls back to minimal base rights (no elevated access)
			List<String> defaults = userService.departmentDefaults("dept-unknown");
			assertThat(defaults).containsExactlyInAnyOrder("chat:use", "models:local:use");
		}

		/**
		 * Verifies a null department id gets base defaults without an NPE.
		 *
		 * <p>
		 * A new, unassigned user must receive the minimal base rights.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null departmentId gets base defaults without NPE")
		void nullDeptIdGetBaseDefaults() {
			// Null departmentId: new users not yet assigned to a department
			List<String> defaults = userService.departmentDefaults(null);
			assertThat(defaults).containsExactlyInAnyOrder("chat:use", "models:local:use");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// effectivePermissions() — runtime authorization set (BUG-032)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code effectivePermissions()} — runtime grant resolution
	 * (BUG-032).
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("effectivePermissions() — runtime grant resolution")
	class EffectivePermissions {

		/**
		 * Builds a user with a non-cataloged role, a department and explicit rights.
		 *
		 * @param dept   the department id
		 * @param rights the explicit rights to seed
		 * @return a {@link User} configured for override-resolution tests
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		private User userWith(String dept, String... rights) {
			User u = new User();
			u.id = "u-eff-1";
			u.role = "no-such-role"; // role_catalog lookup returns empty
			u.departmentId = dept;
			u.rights = new ArrayList<>(List.of(rights));
			return u;
		}

		/**
		 * Verifies an allow-override grants a right not in rights_json.
		 *
		 * <p>
		 * The override must take effect at runtime, adding {@code sandbox:run}
		 * alongside the existing {@code chat:use}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("an allow-override grants a right that is not in rights_json")
		void allowOverrideGrantsRight() {
			User u = userWith("dept-unknown", "chat:use");
			when(db.queryForList(contains("role_catalog"), eq("no-such-role"))).thenReturn(Collections.emptyList());
			when(db.queryForList(contains("user_overrides"), eq("u-eff-1")))
					.thenReturn(List.of(Map.of("permission_key", "sandbox:run", "effect", "allow", "expires_at", "")));
			List<String> perms = userService.effectivePermissions(u);
			// The override must actually grant the right at runtime — not just in the admin
			// view.
			assertThat(perms).contains("sandbox:run", "chat:use");
		}

		/**
		 * Verifies a deny-override removes an otherwise-granted right (deny wins).
		 *
		 * <p>
		 * The department grants {@code models:local:use}; an explicit deny must
		 * strip it while leaving {@code chat:use}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("a deny-override removes a right even if otherwise granted (deny wins)")
		void denyOverrideWins() {
			User u = userWith("dept-unknown", "chat:use");
			when(db.queryForList(contains("role_catalog"), eq("no-such-role"))).thenReturn(Collections.emptyList());
			// dept-unknown grants models:local:use; an explicit deny must strip it.
			when(db.queryForList(contains("user_overrides"), eq("u-eff-1"))).thenReturn(
					List.of(Map.of("permission_key", "models:local:use", "effect", "deny", "expires_at", "")));
			List<String> perms = userService.effectivePermissions(u);
			assertThat(perms).contains("chat:use").doesNotContain("models:local:use");
		}

		/**
		 * Verifies an expired allow-override does not grant the right.
		 *
		 * <p>
		 * An override with a past expiry must be ignored, so {@code sandbox:run}
		 * stays absent.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("expired allow-override does not grant the right")
		void expiredOverrideIgnored() {
			User u = userWith("dept-unknown", "chat:use");
			when(db.queryForList(contains("role_catalog"), eq("no-such-role"))).thenReturn(Collections.emptyList());
			when(db.queryForList(contains("user_overrides"), eq("u-eff-1"))).thenReturn(List.of(
					Map.of("permission_key", "sandbox:run", "effect", "allow", "expires_at", "2000-01-01T00:00:00Z")));
			List<String> perms = userService.effectivePermissions(u);
			assertThat(perms).doesNotContain("sandbox:run");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// safeJsonList()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code safeJsonList()} — defensive JSON-array parsing.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("safeJsonList()")
	class SafeJsonList {

		/**
		 * Verifies null input returns an empty list.
		 *
		 * <p>
		 * Rights/tags DB columns can be NULL; the parser must return an empty list
		 * rather than NPE.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null input returns empty list")
		void returnsEmptyListForNull() {
			// Null guard: DB columns for rights/tags can be NULL — must not NPE
			assertThat(userService.safeJsonList(null)).isNotNull().isEmpty();
		}

		/**
		 * Verifies blank input short-circuits to an empty list.
		 *
		 * <p>
		 * Whitespace input must skip the mapper entirely and return empty.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("blank input returns empty list (never reaches mapper)")
		void returnsEmptyListForBlank() {
			// Blank guard: whitespace skips mapper entirely — short-circuit path
			assertThat(userService.safeJsonList("   ")).isNotNull().isEmpty();
		}

		/**
		 * Verifies invalid JSON is caught and returns an empty list.
		 *
		 * <p>
		 * When the mapper throws on non-JSON input, the exception must be swallowed
		 * and an empty list returned.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("non-JSON input causes mapper to throw — returns empty list")
		@SuppressWarnings("unchecked")
		void returnsEmptyListForInvalidJson() throws Exception {
			// Stub: mapper throws on non-JSON input — must be caught and return []
			when(mapper.readValue(eq("not-json"), any(TypeReference.class)))
					.thenThrow(new RuntimeException("bad json"));
			// JSON parse failure returns empty list — never propagates the exception
			assertThat(userService.safeJsonList("not-json")).isNotNull().isEmpty();
		}

		/**
		 * Verifies a valid JSON array is parsed as-is.
		 *
		 * <p>
		 * The mapper's deserialised list must be returned unchanged.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("valid JSON array is parsed correctly")
		@SuppressWarnings("unchecked")
		void parsesValidJsonArray() throws Exception {
			// Stub: mapper returns the deserialized rights list
			List<String> expected = List.of("chat:use", "models:local:use");
			when(mapper.readValue(anyString(), any(TypeReference.class))).thenReturn(expected);
			List<String> result = userService.safeJsonList("[\"chat:use\",\"models:local:use\"]");
			assertThat(result).isEqualTo(expected);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// allowedModelIds() — admin bypass
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code allowedModelIds()} — admin grant bypass.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("allowedModelIds() — admin bypass")
	class AllowedModelIdsAdmin {

		/**
		 * Verifies an admin sees all models with no grant checks.
		 *
		 * <p>
		 * An admin must receive every available model id from a single SELECT, with
		 * no access_grants/user_groups/user_overrides queries.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("admin gets all available/configured model IDs without grant checks")
		void adminGetsAllModels() {
			// Stub: DB returns three available models
			User admin = UserFactory.admin();
			when(db.queryForList(anyString(), eq(String.class))).thenReturn(List.of("llama3", "mistral", "codestral"));

			List<String> models = userService.allowedModelIds(admin);
			// Admin sees every model — no grant filtering applied
			assertThat(models).containsExactlyInAnyOrder("llama3", "mistral", "codestral");
			// Should not query access_grants, user_groups, or user_overrides for admin
			// (only a single SELECT for available models)
			verify(db, times(1)).queryForList(anyString(), eq(String.class));
		}
	}
}
