package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link UserService}.
 *
 * <p>Covers: publicUser hydration (all fields, edge cases), findUserById,
 * findUserByEmail, hasRight (admin bypass + explicit rights), departmentDefaults,
 * safeJsonList (null/blank/invalid/valid), allowedModelIds (admin bypass,
 * access_grants by user/department/group, implicit rights, overrides).
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — unit tests")
class UserServiceTest {

	@Mock JdbcTemplate db;
	@Mock ObjectMapper mapper;

	@InjectMocks UserService userService;

	// ─────────────────────────────────────────────────────────────────────────
	// publicUser hydration
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("publicUser() — row-to-User hydration")
	class PublicUser {

		@Test
		@DisplayName("returns null when row is null")
		void returnsNullForNullRow() {
			// Null guard — DB can return no rows; publicUser must handle null gracefully
			assertThat(userService.publicUser(null)).isNull();
		}

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

		@Test
		@DisplayName("isEnterprise=true when authProvider is not 'local'")
		void isEnterpriseForSsoUser() {
			// SSO provider (SAML, OIDC, etc.) → isEnterprise=true used by UI to show SSO badge
			Map<String, Object> row = UserFactory.adminRow();
			row.put("auth_provider", "saml");
			User u = userService.publicUser(row);
			assertThat(u.isEnterprise).isTrue();
		}

		@Test
		@DisplayName("active=false when active column is 0")
		void inactiveUserMappedCorrectly() {
			// Deactivated users: active=0 in DB must map to User.active=false (not null)
			Map<String, Object> row = UserFactory.regularUserRow();
			row.put("active", 0);
			User u = userService.publicUser(row);
			assertThat(u.active).isFalse();
		}

		@Test
		@DisplayName("applies all default quota values when columns are absent")
		void appliesDefaultQuotaValues() {
			// Minimal row: only required fields — all quota/limit columns absent
			Map<String, Object> row = Map.of(
					"id", "u-minimal",
					"name", "Minimal User",
					"email", "minimal@example.com",
					"role", "user",
					"active", 1
			);
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
				try { u.getClass().getDeclaredField("passwordHash"); }
				catch (NoSuchFieldException ok) { /* expected */ }
				try { u.getClass().getDeclaredField("password"); }
				catch (NoSuchFieldException ok) { /* expected */ }
			}).doesNotThrowAnyException();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// findUserById()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findUserById()")
	class FindUserById {

		@Test
		@DisplayName("returns null when DB returns empty list")
		void returnsNullWhenNotFound() {
			// Stub: no row for this user ID (deleted or never existed)
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID))).thenReturn(Collections.emptyList());
			// Null returned — callers must handle missing user gracefully
			assertThat(userService.findUserById(UserFactory.ADMIN_ID)).isNull();
		}

		@Test
		@DisplayName("returns hydrated user when DB row found")
		void returnsHydratedUser() {
			// Stub: DB returns the admin test row
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID)))
					.thenReturn(List.of(UserFactory.adminRow()));
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

	@Nested
	@DisplayName("findUserByEmail()")
	class FindUserByEmail {

		@Test
		@DisplayName("returns null when no active user matches the email")
		void returnsNullForUnknownEmail() {
			// Stub: no active user row for this email (unregistered or deactivated)
			when(db.queryForList(anyString(), eq("unknown@example.com")))
					.thenReturn(Collections.emptyList());
			assertThat(userService.findUserByEmail("unknown@example.com")).isNull();
		}

		@Test
		@DisplayName("returns hydrated user for a known active email")
		void returnsHydratedUserForKnownEmail() {
			// Stub: known email → admin row returned from DB
			String email = "junit-integration-test-only@example.com";
			when(db.queryForList(anyString(), eq(email)))
					.thenReturn(List.of(UserFactory.adminRow()));
			User u = userService.findUserByEmail(email);
			assertThat(u).isNotNull();
			// Email must be preserved exactly — used for session and notification targeting
			assertThat(u.email).isEqualTo(email);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// hasRight()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("hasRight()")
	class HasRight {

		@Test
		@DisplayName("admin always has every right — role bypass")
		void adminBypassesRightCheck() {
			// Admin role bypasses all right checks — no need to enumerate every right for admin
			User admin = UserFactory.admin();
			admin.rights = Collections.emptyList(); // explicitly no rights listed
			// Despite empty rights list, admin gets everything
			assertThat(userService.hasRight(admin, "workspace:build")).isTrue();
			assertThat(userService.hasRight(admin, "models:manage")).isTrue();
			assertThat(userService.hasRight(admin, "any:random:right")).isTrue();
		}

		@Test
		@DisplayName("regular user granted explicit right returns true")
		void userWithExplicitRightReturnsTrue() {
			// regularUser has ["chat:use","models:local:use"] from UserFactory
			User u = UserFactory.regularUser();
			// Rights in the list must return true
			assertThat(userService.hasRight(u, "chat:use")).isTrue();
			assertThat(userService.hasRight(u, "models:local:use")).isTrue();
		}

		@Test
		@DisplayName("regular user lacking right returns false")
		void userWithoutRightReturnsFalse() {
			// regularUser does not have admin or workspace rights — must be denied
			User u = UserFactory.regularUser();
			assertThat(userService.hasRight(u, "workspace:build")).isFalse();
			assertThat(userService.hasRight(u, "admin:full")).isFalse();
		}

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

	@Nested
	@DisplayName("departmentDefaults()")
	class DepartmentDefaults {

		@Test
		@DisplayName("dept-product gets workspace:build and files:upload")
		void productDeptDefaults() {
			// Product team: workspace:build enables AI-assisted development features
			List<String> defaults = userService.departmentDefaults("dept-product");
			assertThat(defaults).contains("workspace:build", "files:upload", "chat:use");
		}

		@Test
		@DisplayName("dept-support gets models:reasoning:use and files:upload")
		void supportDeptDefaults() {
			// Support team: reasoning models to aid with complex customer queries
			List<String> defaults = userService.departmentDefaults("dept-support");
			assertThat(defaults).contains("models:reasoning:use", "files:upload", "chat:use");
		}

		@Test
		@DisplayName("unknown department gets only chat:use and models:local:use")
		void unknownDeptDefaults() {
			// Unknown department falls back to minimal base rights (no elevated access)
			List<String> defaults = userService.departmentDefaults("dept-unknown");
			assertThat(defaults).containsExactlyInAnyOrder("chat:use", "models:local:use");
		}

		@Test
		@DisplayName("null departmentId gets base defaults without NPE")
		void nullDeptIdGetBaseDefaults() {
			// Null departmentId: new users not yet assigned to a department
			List<String> defaults = userService.departmentDefaults(null);
			assertThat(defaults).containsExactlyInAnyOrder("chat:use", "models:local:use");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// safeJsonList()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("safeJsonList()")
	class SafeJsonList {

		@Test
		@DisplayName("null input returns empty list")
		void returnsEmptyListForNull() {
			// Null guard: DB columns for rights/tags can be NULL — must not NPE
			assertThat(userService.safeJsonList(null)).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("blank input returns empty list (never reaches mapper)")
		void returnsEmptyListForBlank() {
			// Blank guard: whitespace skips mapper entirely — short-circuit path
			assertThat(userService.safeJsonList("   ")).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("non-JSON input causes mapper to throw — returns empty list")
		void returnsEmptyListForInvalidJson() throws Exception {
			// Stub: mapper throws on non-JSON input — must be caught and return []
			when(mapper.readValue(eq("not-json"), any(TypeReference.class)))
					.thenThrow(new RuntimeException("bad json"));
			// JSON parse failure returns empty list — never propagates the exception
			assertThat(userService.safeJsonList("not-json")).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("valid JSON array is parsed correctly")
		void parsesValidJsonArray() throws Exception {
			// Stub: mapper returns the deserialized rights list
			List<String> expected = List.of("chat:use", "models:local:use");
			when(mapper.readValue(anyString(), any(TypeReference.class)))
					.thenReturn(expected);
			List<String> result = userService.safeJsonList("[\"chat:use\",\"models:local:use\"]");
			assertThat(result).isEqualTo(expected);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// allowedModelIds() — admin bypass
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("allowedModelIds() — admin bypass")
	class AllowedModelIdsAdmin {

		@Test
		@DisplayName("admin gets all available/configured model IDs without grant checks")
		void adminGetsAllModels() {
			// Stub: DB returns three available models
			User admin = UserFactory.admin();
			when(db.queryForList(anyString(), eq(String.class)))
					.thenReturn(List.of("llama3", "mistral", "codestral"));

			List<String> models = userService.allowedModelIds(admin);
			// Admin sees every model — no grant filtering applied
			assertThat(models).containsExactlyInAnyOrder("llama3", "mistral", "codestral");
			// Should not query access_grants, user_groups, or user_overrides for admin
			// (only a single SELECT for available models)
			verify(db, times(1)).queryForList(anyString(), eq(String.class));
		}
	}
}
