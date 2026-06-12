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
 * <h3>Why this class exists</h3>
 * <p>
 * The auth controller is the login/logout/identity surface and is where most
 * authentication security properties are enforced: uniform error messages (no
 * user enumeration), IP-based rate limiting with trusted-proxy handling, CSRF
 * protection on logout, and correct post-login redirects. These tests pin each
 * of those behaviours with all collaborators mocked so the controller logic is
 * exercised in isolation.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All dependencies ({@link AuthService}, {@link UserService},
 * {@link ChatService}, {@link JdbcTemplate}, servlet request/response) are
 * Mockito mocks injected into the controller.</li>
 * <li>{@link ReflectionTestUtils} sets the {@code trustedProxy} field so
 * X-Forwarded-For handling can be toggled per test.</li>
 * <li>Helper methods build user rows whose BCrypt hash actually matches
 * {@link #PLAIN_PASSWORD}, so the real password check runs.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — login/logout/me coverage including rate-limit, CSRF and
 * enumeration-resistance assertions.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — unit tests")
class AuthControllerTest {

	/** Mocked auth service handling sessions and token extraction. */
	@Mock
	AuthService authService;
	/** Mocked user service mapping DB rows to public {@link User} objects. */
	@Mock
	UserService userService;
	/** Mocked chat service used for audit logging on login. */
	@Mock
	ChatService chatService;
	/** Mocked JDBC template backing user lookups and the login-attempt counter. */
	@Mock
	JdbcTemplate db;
	/** Mocked inbound request supplying headers and the remote address. */
	@Mock
	HttpServletRequest req;
	/** Mocked response used to set/clear session cookies. */
	@Mock
	HttpServletResponse res;

	/** Controller under test with all mocks injected. */
	@InjectMocks
	AuthController controller;

	/** Plaintext password matching {@link UserFactory#BCRYPT_HASH}. */
	private static final String PLAIN_PASSWORD = "test-password-only";

	/**
	 * Resets the {@code trustedProxy} field to empty before each test so no
	 * proxy is trusted unless a test opts in.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@BeforeEach
	void setup() {
		ReflectionTestUtils.setField(controller, "trustedProxy", "");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/auth/login
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code POST /api/auth/login} — validation, auth, rate limiting.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("POST /api/auth/login")
	class Login {

		/**
		 * Verifies a missing email yields 400 with {@code ok=false}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a missing password yields 400.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("400 when password field is missing from request body")
		void returns400WhenPasswordMissing() {
			Map<String, Object> body = Map.of("email", "admin@example.com");
			stubNoRateLimit();

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		/**
		 * Verifies blank email and password are treated as missing (400).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("400 when both email and password are blank strings")
		void returns400WhenBothBlank() {
			Map<String, Object> body = Map.of("email", "  ", "password", "  ");
			stubNoRateLimit();

			ResponseEntity<Map<String, Object>> result = controller.login(body, req, res);
			// Blank strings are treated the same as missing fields
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		/**
		 * Verifies an unknown email yields 401 with the generic message.
		 *
		 * <p>
		 * SECURITY: the error must read "Invalid email or password" so a missing
		 * user cannot be distinguished from a wrong password (no enumeration).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a wrong password yields 401 with the same generic message.
		 *
		 * <p>
		 * Using a known admin row but the wrong password must produce the identical
		 * "Invalid email or password" text, preventing enumeration.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a successful admin login returns 200 with admin redirect.
		 *
		 * <p>
		 * On valid admin credentials the response must be {@code ok=true} with
		 * {@code redirectTo=/admin} and the public user, and the session must be
		 * set and the login audited.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a successful regular-user login redirects to {@code /app}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies an IP over the attempt limit gets 429 before any user lookup.
		 *
		 * <p>
		 * With 10 prior failures and a future reset, the controller must short-
		 * circuit to 429 "Too many" rather than querying the user table.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies the rate-limit counter is incremented on a failed login.
		 *
		 * <p>
		 * With no prior attempts and an unknown user, the first failure must upsert
		 * the login-attempt count to 1 for the client IP.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a successful login purges the IP's rate-limit record.
		 *
		 * <p>
		 * After a valid admin login, the {@code login_attempts} row for the client
		 * IP must be deleted so future logins start clean.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a trusted proxy uses the X-Forwarded-For client IP.
		 *
		 * <p>
		 * When {@code trustedProxy} matches the remote address, the rate-limit
		 * lookup and increment must use the real client IP from X-Forwarded-For,
		 * not the proxy IP.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

	/**
	 * Tests for {@code POST /api/auth/logout} — CSRF guard and session clearing.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("POST /api/auth/logout")
	class Logout {

		/**
		 * Verifies a missing CSRF header yields 403.
		 *
		 * <p>
		 * Without {@code X-Requested-With}, logout must be rejected to prevent
		 * CSRF logout attacks.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("403 when X-Requested-With header is missing")
		void returns403WhenCsrfHeaderMissing() {
			// Missing CSRF header → logout rejected to prevent CSRF logout attacks
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.logout(req, res);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(result.getBody()).containsEntry("ok", false);
		}

		/**
		 * Verifies a valid logout returns 200 and clears the session.
		 *
		 * <p>
		 * With the CSRF header present and a token extracted, the response is 200
		 * {@code ok=true} and {@code clearSession} is called with that token.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies logout is idempotent when no token is present.
		 *
		 * <p>
		 * Even with a null token (already logged out), the response is 200 and
		 * {@code clearSession} is still invoked.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

	/**
	 * Tests for {@code GET /api/auth/me} — identity reporting.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GET /api/auth/me")
	class Me {

		/**
		 * Verifies an authenticated session returns the user.
		 *
		 * <p>
		 * With a user in the request attribute, the response is 200 with
		 * {@code authenticated=true} and the user object.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies no session returns {@code authenticated=false} with null user.
		 *
		 * <p>
		 * By design this is 200 (not 401) with an explicit null user in the body,
		 * so the client can render a logged-out state cleanly.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

	/**
	 * Stubs the login-attempt lookup to report no prior failures for any IP.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private void stubNoRateLimit() {
		when(db.queryForList(contains("login_attempts"), anyString())).thenReturn(Collections.emptyList());
	}

	/**
	 * Builds an admin user row whose password hash matches {@link #PLAIN_PASSWORD}.
	 *
	 * @return an admin row with a freshly computed BCrypt hash
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private Map<String, Object> adminRowWithHash() {
		Map<String, Object> row = new HashMap<>(UserFactory.adminRow());
		// Replace the test hash with one that actually matches PLAIN_PASSWORD
		row.put("password_hash", BCrypt.hashpw(PLAIN_PASSWORD, BCrypt.gensalt(4)));
		return row;
	}

	/**
	 * Builds a regular user row whose password hash matches {@link #PLAIN_PASSWORD}.
	 *
	 * @return a regular-user row with a freshly computed BCrypt hash
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private Map<String, Object> userRowWithHash() {
		Map<String, Object> row = new HashMap<>(UserFactory.regularUserRow());
		row.put("password_hash", BCrypt.hashpw(PLAIN_PASSWORD, BCrypt.gensalt(4)));
		return row;
	}
}
