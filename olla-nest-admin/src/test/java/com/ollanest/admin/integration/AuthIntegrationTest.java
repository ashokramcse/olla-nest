package com.ollanest.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.admin.OllaNestAdminApplication;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.WhisperServerManager;

/**
 * Integration tests for the auth flow running against the full Admin Spring
 * Boot context.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Unlike the controller unit tests, these exercise the entire request pipeline
 * — filters, security headers, JSON binding, the real auth controller and a
 * live (H2) database — so they catch wiring and end-to-end regressions that
 * mocks cannot. They lock the login/logout contract, the enumeration-resistant
 * error responses, session-cookie attributes (HttpOnly, SameSite=Lax), the
 * security-header filter, and the global error envelope.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs a full {@link SpringBootTest} on a random port with
 * {@link AutoConfigureMockMvc} and the {@code test} profile, seeded from
 * {@code /test-data.sql} once per class.</li>
 * <li>An H2 in-memory database (SQLite-mode) backs the test; external services
 * (Ollama, Whisper) and the SQLite-specific {@link DatabaseService} are
 * replaced with {@link MockitoBean}s so the suite runs offline.</li>
 * <li>{@link DirtiesContext} resets the context after the class to avoid state
 * bleed into other integration tests.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — end-to-end login/logout, security-header and error-envelope
 * coverage on the admin app.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@SpringBootTest(classes = OllaNestAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = { "/test-data.sql" }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Auth Integration Tests — Admin app (H2)")
class AuthIntegrationTest {

	/** MockMvc entry point driving HTTP requests through the full filter chain. */
	@Autowired
	MockMvc mockMvc;
	/** Live H2 JDBC template used to seed users and assert on persisted sessions. */
	@Autowired
	JdbcTemplate db;
	/** Autowired JSON mapper from the application context. */
	@Autowired
	ObjectMapper mapper;

	// Mock out external services and SQLite-specific infrastructure
	/** Mocked database service — avoids PRAGMA/INSERT OR REPLACE SQLite-isms under H2. */
	@MockitoBean
	DatabaseService databaseService; // avoids PRAGMA/INSERT OR REPLACE SQLite-isms
	/** Mocked Ollama service so no local model runtime is required. */
	@MockitoBean
	OllamaService ollamaService;
	/** Mocked Whisper manager so no speech runtime is required. */
	@MockitoBean
	WhisperServerManager whisperServerManager;

	/** Plaintext password seeded for the integration admin user. */
	private static final String PLAIN_PASSWORD = "junit-test-password-only";
	/** Email of the seeded integration admin user. */
	private static final String ADMIN_EMAIL = "junit-admin-integ@example.com";

	/**
	 * Inserts a fresh admin user with the given BCrypt-hashed password.
	 *
	 * <p>
	 * Any existing row for the email is removed first so the seed is idempotent
	 * within a test.
	 *
	 * @param email         the admin email to seed
	 * @param plainPassword the plaintext password to hash and store
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private void seedAdmin(String email, String plainPassword) {
		String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(4));
		db.update("DELETE FROM users WHERE email = ?", email);
		db.update(
				"INSERT INTO users (id, name, email, role, active, password_hash, auth_provider) "
						+ "VALUES (?, ?, ?, 'admin', 1, ?, 'local')",
				"u-integ-admin-" + System.nanoTime(), "Integration Admin", email, hash);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/auth/me (safe, no auth required for the endpoint itself)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code GET /api/auth/me} — unauthenticated identity reporting.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GET /api/auth/me")
	class Me {

		/**
		 * Verifies that with no session cookie the endpoint reports
		 * {@code authenticated=false} and a null user.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns authenticated=false when no session cookie sent")
		void returnsUnauthenticatedWithoutCookie() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk())
					.andExpect(jsonPath("$.authenticated").value(false))
					.andExpect(jsonPath("$.user").value(nullValue()));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/auth/login
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code POST /api/auth/login} — validation, auth and session.
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
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("400 when request body has no email field")
		void returns400ForMissingEmail() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"password\":\"" + PLAIN_PASSWORD + "\"}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.ok").value(false));
		}

		/**
		 * Verifies an empty password yields 400 with {@code ok=false}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("400 when request body has empty password")
		void returns400ForEmptyPassword() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"admin@test.com\",\"password\":\"\"}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.ok").value(false));
		}

		/**
		 * Verifies a completely empty JSON body yields 400.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("400 when request body is completely empty JSON")
		void returns400ForEmptyBody() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest());
		}

		/**
		 * Verifies an unknown email yields 401 with the generic error message.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("401 when email does not exist")
		void returns401ForUnknownEmail() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"noone@nowhere.com\",\"password\":\"anything\"}"))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.ok").value(false))
					.andExpect(jsonPath("$.error").value("Invalid email or password"));
		}

		/**
		 * Verifies a wrong password yields the same 401 message (no enumeration).
		 *
		 * <p>
		 * SECURITY: the error must be identical to the unknown-email case so the
		 * two are indistinguishable.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("401 when password is wrong — same error as unknown email (no enumeration)")
		void returns401ForWrongPassword() throws Exception {
			seedAdmin(ADMIN_EMAIL, PLAIN_PASSWORD);
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"wrong-password\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.error").value("Invalid email or password"));
		}

		/**
		 * Verifies a successful admin login sets a secure cookie and persists a
		 * session.
		 *
		 * <p>
		 * The response must be {@code ok=true} with {@code redirectTo=/admin} and
		 * the admin user; at least one {@code Set-Cookie} header must carry the
		 * session cookie with {@code HttpOnly} and {@code SameSite=Lax}; and a
		 * session row must exist in the DB.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("200 OK on successful admin login — cookie set, ok=true, redirectTo=/admin")
		void returns200OnSuccessfulAdminLogin() throws Exception {
			seedAdmin(ADMIN_EMAIL, PLAIN_PASSWORD);
			db.update("DELETE FROM login_attempts"); // clear any stale state

			MvcResult result = mockMvc
					.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + PLAIN_PASSWORD + "\"}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
					.andExpect(jsonPath("$.redirectTo").value("/admin"))
					.andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
					.andExpect(jsonPath("$.user.role").value("admin")).andReturn();

			// Session cookie must be set — check all Set-Cookie headers (SameSite=Lax is in
			// the
			// override header added via addHeader(), which may differ from the Servlet API
			// cookie)
			List<String> setCookieHeaders = result.getResponse().getHeaders("Set-Cookie");
			assertThat(setCookieHeaders).isNotEmpty();
			String allCookies = String.join("; ", setCookieHeaders);
			assertThat(allCookies).contains("olla_nest_session");
			assertThat(allCookies).contains("HttpOnly");
			// SameSite=Lax is written via the explicit addHeader override in AuthService
			boolean hasSameSite = setCookieHeaders.stream().anyMatch(h -> h.contains("SameSite=Lax"));
			assertThat(hasSameSite).as("At least one Set-Cookie header must contain SameSite=Lax").isTrue();

			// Verify session was persisted to DB
			int sessionCount = db.queryForObject(
					"SELECT COUNT(*) FROM sessions WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
					Integer.class, ADMIN_EMAIL);
			assertThat(sessionCount).isGreaterThan(0);
		}

		/**
		 * Verifies a non-JSON content type yields 415.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("415 when Content-Type is not application/json")
		void returns415ForWrongContentType() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("email=x&password=y"))
					.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.ok").value(false));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/auth/logout
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code POST /api/auth/logout} — CSRF guard and cookie clearing.
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
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("403 when X-Requested-With header is missing (CSRF guard)")
		void returns403ForMissingCsrfHeader() throws Exception {
			mockMvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.ok").value(false));
		}

		/**
		 * Verifies a logout with the CSRF header returns 200 and expires the cookie.
		 *
		 * <p>
		 * The {@code Set-Cookie} header must include {@code Max-Age=0} to clear the
		 * session.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("200 OK when X-Requested-With header is present — cookie cleared")
		void returns200AndClearsCookie() throws Exception {
			MvcResult result = mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "XMLHttpRequest"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn();

			String setCookie = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookie).contains("Max-Age=0");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Security headers (checked via filter on any response)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for the {@code SecurityHeadersFilter} — hardening headers on every
	 * response.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("Security headers (SecurityHeadersFilter)")
	class SecurityHeaders {

		/**
		 * Verifies {@code X-Content-Type-Options: nosniff} is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("every response includes X-Content-Type-Options: nosniff")
		void hasContentTypeOptions() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
		}

		/**
		 * Verifies {@code X-Frame-Options: DENY} is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("every response includes X-Frame-Options: DENY")
		void hasXFrameOptions() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().string("X-Frame-Options", "DENY"));
		}

		/**
		 * Verifies a {@code Content-Security-Policy} header is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("every response includes Content-Security-Policy")
		void hasContentSecurityPolicy() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().exists("Content-Security-Policy"));
		}

		/**
		 * Verifies the {@code Referrer-Policy} header value.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("every response includes Referrer-Policy")
		void hasReferrerPolicy() throws Exception {
			mockMvc.perform(get("/api/auth/me"))
					.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
		}

		/**
		 * Verifies HSTS is absent on plain-HTTP test requests.
		 *
		 * <p>
		 * {@code Strict-Transport-Security} must only be emitted over HTTPS, so it
		 * must not appear here.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("HSTS is absent on plain HTTP test requests")
		void noHstsOnPlainHttp() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().doesNotExist("Strict-Transport-Security"));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GlobalExceptionHandler (404 / 405)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for the {@code GlobalExceptionHandler} — error envelope shape.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("GlobalExceptionHandler — error envelope shape")
	class ExceptionHandling {

		/**
		 * Verifies an unknown API path returns 404 with the standard envelope.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("unknown API path returns 404 with {ok:false, error:'Not found'}")
		void returns404ForUnknownApiPath() throws Exception {
			mockMvc.perform(get("/api/does-not-exist-at-all")).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Not found"));
		}

		/**
		 * Verifies a wrong HTTP method on a known endpoint returns 405.
		 *
		 * <p>
		 * {@code GET /api/auth/login} must be 405 since login only accepts POST.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("wrong HTTP method on known endpoint returns 405")
		void returns405ForWrongMethod() throws Exception {
			// GET /api/auth/login should be 405 (login only accepts POST)
			mockMvc.perform(get("/api/auth/login")).andExpect(status().isMethodNotAllowed())
					.andExpect(jsonPath("$.ok").value(false));
		}
	}
}
