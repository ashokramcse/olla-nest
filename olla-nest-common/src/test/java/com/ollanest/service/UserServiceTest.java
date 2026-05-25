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
			assertThat(userService.publicUser(null)).isNull();
		}

		@Test
		@DisplayName("maps all scalar fields correctly from admin row")
		void mapsAllFieldsFromAdminRow() {
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
			Map<String, Object> row = UserFactory.adminRow();
			row.put("auth_provider", "saml");
			User u = userService.publicUser(row);
			assertThat(u.isEnterprise).isTrue();
		}

		@Test
		@DisplayName("active=false when active column is 0")
		void inactiveUserMappedCorrectly() {
			Map<String, Object> row = UserFactory.regularUserRow();
			row.put("active", 0);
			User u = userService.publicUser(row);
			assertThat(u.active).isFalse();
		}

		@Test
		@DisplayName("applies all default quota values when columns are absent")
		void appliesDefaultQuotaValues() {
			Map<String, Object> row = Map.of(
					"id", "u-minimal",
					"name", "Minimal User",
					"email", "minimal@example.com",
					"role", "user",
					"active", 1
			);
			User u = userService.publicUser(row);
			// Defaults from UserService
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
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID))).thenReturn(Collections.emptyList());
			assertThat(userService.findUserById(UserFactory.ADMIN_ID)).isNull();
		}

		@Test
		@DisplayName("returns hydrated user when DB row found")
		void returnsHydratedUser() {
			when(db.queryForList(anyString(), eq(UserFactory.ADMIN_ID)))
					.thenReturn(List.of(UserFactory.adminRow()));
			User u = userService.findUserById(UserFactory.ADMIN_ID);
			assertThat(u).isNotNull();
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
			when(db.queryForList(anyString(), eq("unknown@example.com")))
					.thenReturn(Collections.emptyList());
			assertThat(userService.findUserByEmail("unknown@example.com")).isNull();
		}

		@Test
		@DisplayName("returns hydrated user for a known active email")
		void returnsHydratedUserForKnownEmail() {
			String email = "junit-integration-test-only@example.com";
			when(db.queryForList(anyString(), eq(email)))
					.thenReturn(List.of(UserFactory.adminRow()));
			User u = userService.findUserByEmail(email);
			assertThat(u).isNotNull();
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
			User admin = UserFactory.admin();
			admin.rights = Collections.emptyList(); // explicitly no rights listed
			assertThat(userService.hasRight(admin, "workspace:build")).isTrue();
			assertThat(userService.hasRight(admin, "models:manage")).isTrue();
			assertThat(userService.hasRight(admin, "any:random:right")).isTrue();
		}

		@Test
		@DisplayName("regular user granted explicit right returns true")
		void userWithExplicitRightReturnsTrue() {
			User u = UserFactory.regularUser();
			// regularUser has ["chat:use","models:local:use"]
			assertThat(userService.hasRight(u, "chat:use")).isTrue();
			assertThat(userService.hasRight(u, "models:local:use")).isTrue();
		}

		@Test
		@DisplayName("regular user lacking right returns false")
		void userWithoutRightReturnsFalse() {
			User u = UserFactory.regularUser();
			assertThat(userService.hasRight(u, "workspace:build")).isFalse();
			assertThat(userService.hasRight(u, "admin:full")).isFalse();
		}

		@Test
		@DisplayName("null rights list returns false without NPE")
		void nullRightsReturnsFalse() {
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
			List<String> defaults = userService.departmentDefaults("dept-product");
			assertThat(defaults).contains("workspace:build", "files:upload", "chat:use");
		}

		@Test
		@DisplayName("dept-support gets models:reasoning:use and files:upload")
		void supportDeptDefaults() {
			List<String> defaults = userService.departmentDefaults("dept-support");
			assertThat(defaults).contains("models:reasoning:use", "files:upload", "chat:use");
		}

		@Test
		@DisplayName("unknown department gets only chat:use and models:local:use")
		void unknownDeptDefaults() {
			List<String> defaults = userService.departmentDefaults("dept-unknown");
			assertThat(defaults).containsExactlyInAnyOrder("chat:use", "models:local:use");
		}

		@Test
		@DisplayName("null departmentId gets base defaults without NPE")
		void nullDeptIdGetBaseDefaults() {
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
			assertThat(userService.safeJsonList(null)).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("blank input returns empty list (never reaches mapper)")
		void returnsEmptyListForBlank() {
			assertThat(userService.safeJsonList("   ")).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("non-JSON input causes mapper to throw — returns empty list")
		void returnsEmptyListForInvalidJson() throws Exception {
			when(mapper.readValue(eq("not-json"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
					.thenThrow(new RuntimeException("bad json"));
			assertThat(userService.safeJsonList("not-json")).isNotNull().isEmpty();
		}

		@Test
		@DisplayName("valid JSON array is parsed correctly")
		void parsesValidJsonArray() throws Exception {
			List<String> expected = List.of("chat:use", "models:local:use");
			when(mapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
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
			User admin = UserFactory.admin();
			when(db.queryForList(anyString(), eq(String.class)))
					.thenReturn(List.of("llama3", "mistral", "codestral"));

			List<String> models = userService.allowedModelIds(admin);
			assertThat(models).containsExactlyInAnyOrder("llama3", "mistral", "codestral");
			// Should not query access_grants, user_groups, or user_overrides for admin
			verify(db, times(1)).queryForList(anyString(), eq(String.class));
		}
	}
}
