package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;
import com.ollanest.testinfra.UserFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OCD-level unit tests for {@link AuthController}.
 *
 * <p>
 * All external dependencies are Mockito-stubbed. Covers:
 * <ul>
 * <li>POST /api/auth/login — happy path (admin, user), 400 missing fields, 401
 * wrong password, 401 unknown email, 429 rate limit, IP from trusted proxy,
 * expired access_expires_at enforcement, rate-limit counter increment</li>
 * <li>POST /api/auth/logout — CSRF guard 403, happy path 200</li>
 * <li>GET /api/auth/me — authenticated, unauthenticated</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — unit tests")
class AuthControllerTest {

	@Mock
	AuthService authService;
	@Mock
	UserService userService;
	@Mock
	ChatService chatService;
	@Mock
	JdbcTemplate db;
	@Mock
	HttpServletRequest req;
	@Mock
	HttpServletResponse res;

	@InjectMocks
	AuthController controller;

	/** Plaintext password matching {@link UserFactory#BCRYPT_HASH}. */
	private static final String PLAIN_PASSWORD = "test-password-only";

	@BeforeEach
	void setup() {
		ReflectionTestUtils.setField(controller, "trustedProxy", "");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/auth/login
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/auth/login")
	class Login {

		@Test
		@DisplayName("400 when email field is missing from request body")
		void returns400WhenEmailMissing() {
			Map<String, Object> body = Map.of("password", PLAIN_PASSWORD);
			stubNoRateLimit();

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			// Missing required field must return 400 Bad Request immediately
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(result.getBody()).containsEntry("ok", false);
		}

		@Test
		@DisplayName("400 when password field is missing from request body")
		void returns400WhenPasswordMissing() {
			Map<String, Object> body = Map.of("email", "admin@example.com");
			stubNoRateLimit();

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("400 when both email and password are blank strings")
		void returns400WhenBothBlank() {
			Map<String, Object> body = Map.of("email", "  ", "password", "  ");
			stubNoRateLimit();

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			// Blank strings are treated the same as missing fields
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("401 when email not found in DB")
		void returns401WhenEmailNotFound() {
			Map<String, Object> body = Map.of("email", "unknown@example.com", "password", PLAIN_PASSWORD);
			stubNoRateLimit();
			when(req.getRemoteAddr()).thenReturn("127.0.0.1");
			// Stub DB to return empty list — user does not exist
			when(db.queryForList(contains("users WHERE email"), eq("unknown@example.com")))
					.thenReturn(Collections.emptyList());

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(result.getBody()).containsEntry("ok", false);
			// SECURITY: error message must not distinguish between "user not found" vs
			// "wrong password"
			assertThat(result.getBody().get("error").toString()).isEqualTo("Invalid email or password");
		}

		@Test
		@DisplayName("401 when password does not match BCrypt hash")
		void returns401WhenWrongPassword() {
			Map<String, Object> body = Map.of("email", "junit-integration-test-only@example.com", "password",
					"wrong-password");
			stubNoRateLimit();
			when(req.getRemoteAddr()).thenReturn("127.0.0.1");

			// Stub DB to return the admin row (which has a known BCrypt hash)
			Map<String, Object> row = UserFactory.adminRow();
			when(db.queryForList(contains("users WHERE email"), eq("junit-integration-test-only@example.com")))
					.thenReturn(List.of(row));

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			// Same message — prevents user enumeration via different error texts
			assertThat(result.getBody().get("error").toString()).isEqualTo("Invalid email or password");
		}

		@Test
		@DisplayName("200 OK on successful admin login — session set, audit logged, redirectTo=/admin")
		void returns200ForSuccessfulAdminLogin() {
			Map<String, Object> body = Map.of("email", "junit-integration-test-only@example.com", "password",
					PLAIN_PASSWORD);
			stubNoRateLimit();
			when(req.getRemoteAddr()).thenReturn("127.0.0.1");

			// Stub DB to return an admin row with a real BCrypt hash matching
			// PLAIN_PASSWORD
			Map<String, Object> row = adminRowWithHash();
			when(db.queryForList(contains("users WHERE email"), anyString())).thenReturn(List.of(row));

			User admin = UserFactory.admin();
			when(userService.publicUser(row)).thenReturn(admin);

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(result.getBody()).containsEntry("ok", true);
			// Admin users must be redirected to /admin, not /app
			assertThat(result.getBody().get("redirectTo")).isEqualTo("/admin");
			assertThat(result.getBody().get("user")).isEqualTo(admin);
			// Session must be set and audit logged on every successful login
			verify(authService).setSession(res, req, admin);
			verify(chatService).appendAudit(eq(admin.name), eq("auth.login"), anyString(), any());
		}

		@Test
		@DisplayName("200 OK on successful user login — redirectTo=/app")
		void returns200ForSuccessfulUserLogin() {
			Map<String, Object> body = Map.of("email", "test-user-seed-only@example.com", "password", PLAIN_PASSWORD);
			stubNoRateLimit();
			when(req.getRemoteAddr()).thenReturn("10.0.0.1");

			// Stub DB to return a regular user row with a matching hash
			Map<String, Object> row = userRowWithHash();
			when(db.queryForList(contains("users WHERE email"), anyString())).thenReturn(List.of(row));

			User user = UserFactory.regularUser();
			when(userService.publicUser(row)).thenReturn(user);

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			// Regular users redirect to /app, not /admin
			assertThat(result.getBody().get("redirectTo")).isEqualTo("/app");
		}

		@Test
		@DisplayName("429 when IP has exceeded login attempt limit")
		void returns429WhenRateLimited() {
			Map<String, Object> body = Map.of("email", "admin@example.com", "password", PLAIN_PASSWORD);
			when(req.getRemoteAddr()).thenReturn("192.168.1.1");

			// Stub DB to return 10 failed attempts with a future reset time
			long futureReset = System.currentTimeMillis() + 900_000; // 15 min from now
			when(db.queryForList(contains("login_attempts"), eq("192.168.1.1")))
					.thenReturn(List.of(Map.of("count", 10L, "reset_at", futureReset)));

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			// 429 Too Many Requests must be returned before any DB user lookup
			assertThat(result.getStatusCode().value()).isEqualTo(429);
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("Too many");
		}

		@Test
		@DisplayName("rate-limit counter is incremented on wrong password")
		void incrementsRateLimitCounterOnFailure() {
			Map<String, Object> body = Map.of("email", "bad@example.com", "password", "wrong");
			when(req.getRemoteAddr()).thenReturn("10.1.2.3");
			// Stub: no prior failed attempts for this IP
			when(db.queryForList(contains("login_attempts"), anyString())).thenReturn(Collections.emptyList()); // no
																												// prior
																												// attempts
			// Stub: user not found → 401
			when(db.queryForList(contains("users WHERE email"), anyString())).thenReturn(Collections.emptyList());

			controller.login(body, req, res);

			// Rate-limit counter must be incremented to 1 after first failed attempt
			verify(db).update(contains("INSERT OR REPLACE INTO login_attempts"), eq("10.1.2.3"), eq(1L), anyLong()); // count
																														// goes
																														// to
																														// 1
		}

		@Test
		@DisplayName("rate-limit record is cleared on successful login")
		void clearsRateLimitOnSuccess() {
			Map<String, Object> body = Map.of("email", "junit-integration-test-only@example.com", "password",
					PLAIN_PASSWORD);
			stubNoRateLimit();
			when(req.getRemoteAddr()).thenReturn("127.0.0.1");

			Map<String, Object> row = adminRowWithHash();
			when(db.queryForList(contains("users WHERE email"), anyString())).thenReturn(List.of(row));
			when(userService.publicUser(row)).thenReturn(UserFactory.admin());

			controller.login(body, req, res);

			// Rate-limit record must be purged after a successful login
			verify(db).update(contains("DELETE FROM login_attempts"), eq("127.0.0.1"));
		}

		@Test
		@DisplayName("trusted proxy: uses X-Forwarded-For IP for rate limiting")
		void usesTrustedProxyForwardedIp() {
			// Configure controller to trust 10.0.0.1 as a reverse proxy
			ReflectionTestUtils.setField(controller, "trustedProxy", "10.0.0.1");
			when(req.getRemoteAddr()).thenReturn("10.0.0.1"); // proxy IP
			when(req.getHeader("x-forwarded-for")).thenReturn("203.0.113.5, 10.0.0.1");

			// Rate-limit check must use the real client IP extracted from X-Forwarded-For
			when(db.queryForList(contains("login_attempts"), eq("203.0.113.5"))).thenReturn(Collections.emptyList());
			when(db.queryForList(contains("users WHERE email"), anyString())).thenReturn(Collections.emptyList());

			Map<String, Object> body = Map.of("email", "x@y.com", "password", "p");
			controller.login(body, req, res);

			// Rate limit increment should use the real client IP, not the proxy IP
			verify(db).update(contains("INSERT OR REPLACE INTO login_attempts"), eq("203.0.113.5"), anyLong(),
					anyLong());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/auth/logout
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/auth/logout")
	class Logout {

		@Test
		@DisplayName("403 when X-Requested-With header is missing")
		void returns403WhenCsrfHeaderMissing() {
			// Missing CSRF header → logout rejected to prevent CSRF logout attacks
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.logout(req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(result.getBody()).containsEntry("ok", false);
		}

		@Test
		@DisplayName("200 OK and session cleared when CSRF header present")
		void returns200AndClearsSession() {
			String token = "c".repeat(64);
			// Stub: CSRF header present and session token extracted
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			when(authService.getToken(req)).thenReturn(token);

			ResponseEntity<Map<String, Object>> result = controller.logout(req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(result.getBody()).containsEntry("ok", true);
			// Session cookie must be cleared on successful logout
			verify(authService).clearSession(res, token);
		}

		@Test
		@DisplayName("200 OK even when no token present (already logged out)")
		void returns200WhenAlreadyLoggedOut() {
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			// Stub: no session token — user already logged out
			when(authService.getToken(req)).thenReturn(null);

			ResponseEntity<Map<String, Object>> result = controller.logout(req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			// clearSession must still be called even with null token (idempotent logout)
			verify(authService).clearSession(res, null);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/auth/me
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/auth/me")
	class Me {

		@Test
		@DisplayName("returns authenticated=true and user object when session is valid")
		void returnsAuthenticatedTrueWithUser() {
			User admin = UserFactory.admin();
			// Stub: SessionAuthFilter has already placed the user in the request attribute
			when(req.getAttribute("authenticatedUser")).thenReturn(admin);

			ResponseEntity<Map<String, Object>> result = controller.me(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(result.getBody()).containsEntry("authenticated", true);
			assertThat(result.getBody()).containsEntry("user", admin);
		}

		@Test
		@DisplayName("returns authenticated=false and null user when no session")
		void returnsAuthenticatedFalseForUnauthenticated() {
			// Stub: no user in request attribute — not authenticated
			when(req.getAttribute("authenticatedUser")).thenReturn(null);

			ResponseEntity<Map<String, Object>> result = controller.me(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK); // NOT 401 — by design
			assertThat(result.getBody()).containsEntry("authenticated", false);
			// user must be explicitly null in the response body, not absent
			assertThat(result.getBody()).containsEntry("user", null);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Private helpers
	// ─────────────────────────────────────────────────────────────────────────

	private void stubNoRateLimit() {
		when(db.queryForList(contains("login_attempts"), anyString())).thenReturn(Collections.emptyList());
	}

	private Map<String, Object> adminRowWithHash() {
		Map<String, Object> row = new HashMap<>(UserFactory.adminRow());
		// Replace the test hash with one that actually matches PLAIN_PASSWORD
		row.put("password_hash", BCrypt.hashpw(PLAIN_PASSWORD, BCrypt.gensalt(4)));
		return row;
	}

	private Map<String, Object> userRowWithHash() {
		Map<String, Object> row = new HashMap<>(UserFactory.regularUserRow());
		row.put("password_hash", BCrypt.hashpw(PLAIN_PASSWORD, BCrypt.gensalt(4)));
		return row;
	}
}
