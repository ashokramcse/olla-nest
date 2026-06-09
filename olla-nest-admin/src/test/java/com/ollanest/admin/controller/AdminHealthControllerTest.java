package com.ollanest.admin.controller;

import com.ollanest.controller.admin.AdminHealthController;
import com.ollanest.model.User;
import com.ollanest.service.MonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminHealthController}.
 *
 * <p>Verifies: 401 for unauthenticated, 403 for non-admin, 200 with correct
 * response shape for admin, and that DB stats use the correct SQL queries
 * (models uses {@code IN ('available','configured')}, sessions uses
 * {@code datetime('now')}).
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminHealthController — unit tests")
class AdminHealthControllerTest {

	@Mock JdbcTemplate    db;
	@Mock MonitorService  monitorService;
	@Mock HttpServletRequest req;

	private AdminHealthController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminHealthController(db, monitorService);
	}

	private void setAuthenticatedUser(User user) {
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	private User admin() {
		User u = new User();
		u.id   = "u-admin-001";
		u.role = "admin";
		u.email = "admin@example.com";
		return u;
	}

	private User regularUser() {
		User u = new User();
		u.id   = "u-user-001";
		u.role = "user";
		u.email = "user@example.com";
		return u;
	}

	// ── Auth guards ───────────────────────────────────────────────────────────

	@Nested
	@DisplayName("auth guards")
	class AuthGuards {

		@Test
		@DisplayName("returns 401 when no authenticated user")
		void returns401WhenNoUser() {
			setAuthenticatedUser(null);
			// CSRF header is not needed for GET (BaseController requireAdmin doesn't check CSRF)
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(r.getBody()).containsEntry("ok", false);
		}

		@Test
		@DisplayName("returns 403 when authenticated user is not admin")
		void returns403ForNonAdmin() {
			setAuthenticatedUser(regularUser());
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(r.getBody()).containsEntry("ok", false);
		}
	}

	// ── Success response ──────────────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("success (admin user)")
	class SuccessResponse {

		@BeforeEach
		void stubAdmin() {
			setAuthenticatedUser(admin());
			when(req.getMethod()).thenReturn("GET");
			Map<String, Object> snapshot = new LinkedHashMap<>();
			snapshot.put("uptimeMs", 12345L);
			snapshot.put("requests", 99L);
			snapshot.put("errors", 2L);
			snapshot.put("memoryUsedMb", 128L);
			snapshot.put("memoryTotalMb", 512L);
			when(monitorService.getSnapshot()).thenReturn(snapshot);

			// Stub all five COUNT queries — order matters: more specific matchers first
			when(db.queryForObject(contains("chat_sessions"), eq(Integer.class))).thenReturn(42);
			when(db.queryForObject(argThat(s -> s.contains("sessions") && !s.contains("chat_")),
					eq(Integer.class))).thenReturn(7);
			when(db.queryForObject(contains("users"), eq(Integer.class))).thenReturn(5);
			when(db.queryForObject(contains("models"), eq(Integer.class))).thenReturn(3);
			when(db.queryForObject(contains("api_providers"), eq(Integer.class))).thenReturn(2);
		}

		@Test
		@DisplayName("returns 200 OK with ok=true and status=healthy")
		void returns200WithOkAndStatus() {
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(r.getBody()).containsEntry("ok", true);
			assertThat(r.getBody()).containsEntry("status", "healthy");
		}

		@Test
		@DisplayName("response contains all top-level JVM keys from snapshot")
		void responseContainsSnapshotKeys() {
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			Map<String, Object> body = r.getBody();
			assertThat(body).containsKeys("uptimeMs", "memoryUsedMb", "memoryTotalMb", "requests", "errors");
		}

		@Test
		@DisplayName("response contains db sub-object with all 5 stat keys")
		void responseContainsDbSubObject() {
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			@SuppressWarnings("unchecked")
			Map<String, Object> dbStats = (Map<String, Object>) r.getBody().get("db");
			assertThat(dbStats).containsKeys(
					"activeUsers", "activeModels", "activeSessions", "totalChats", "enabledProviders");
		}

		@Test
		@DisplayName("db.activeModels value comes from the correct COUNT query")
		void activeModelsReflectsDbCount() {
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			@SuppressWarnings("unchecked")
			Map<String, Object> dbStats = (Map<String, Object>) r.getBody().get("db");
			assertThat(dbStats.get("activeModels")).isEqualTo(3);
		}

		@Test
		@DisplayName("models query uses IN ('available','configured') — not 'active'")
		void modelsQueryUsesCorrectStatusValues() {
			controller.health(req);
			// Verify the SQL passed to db contains the correct status predicate
			verify(db).queryForObject(
					argThat(sql -> sql.contains("'available'") && sql.contains("'configured'")),
					eq(Integer.class));
		}

		@Test
		@DisplayName("sessions query uses datetime('now') — not Instant.now().toString()")
		void sessionsQueryUsesDatetimeNow() {
			controller.health(req);
			verify(db).queryForObject(
					argThat(sql -> sql.contains("datetime('now')")),
					eq(Integer.class));
		}

		@Test
		@DisplayName("null DB result is safely coerced to 0")
		void nullDbResultIsZero() {
			when(db.queryForObject(contains("users"), eq(Integer.class))).thenReturn(null);
			ResponseEntity<Map<String, Object>> r = controller.health(req);
			@SuppressWarnings("unchecked")
			Map<String, Object> dbStats = (Map<String, Object>) r.getBody().get("db");
			assertThat(dbStats.get("activeUsers")).isEqualTo(0);
		}
	}
}
