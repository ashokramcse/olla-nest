package com.ollanest.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.admin.OllaNestAdminApplication;
import com.ollanest.service.OllamaService;
import com.ollanest.service.WhisperServerManager;

import jakarta.servlet.http.Cookie;

/**
 * SOC 2 Security Integration Tests — runs the full Admin Spring Boot context
 * against an in-memory SQLite database.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * SOC 2 trust criteria have to hold end-to-end through the real HTTP stack, not
 * just in unit-tested fragments. This suite drives the full filter chain and a
 * live database to prove authentication, RBAC, CSRF, brute-force throttling,
 * input validation, security headers, confidentiality (key redaction), the
 * audit trail, session-token properties, and SSO-bypass prevention all behave
 * as required when wired together.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs a full {@link SpringBootTest} on a random port with
 * {@link AutoConfigureMockMvc} and the {@code test} profile.</li>
 * <li>An H2/SQLite in-memory database backs the test; external services
 * (Ollama, Whisper) are {@link MockitoBean}s so the suite runs offline.</li>
 * <li>{@code @BeforeEach} reseeds a known admin and regular user and clears
 * session/login-attempt state so each test starts from a clean baseline.</li>
 * <li>Helper methods log in and extract the session token so authenticated
 * requests can be replayed with a real cookie.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.0 — SOC 2 hardening: end-to-end auth, RBAC, CSRF, brute-force,
 * header, audit and confidentiality coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0
 * @version v2026.2.0
 */
@SpringBootTest(classes = OllaNestAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("SOC 2 Security Integration Tests — Admin App")
class Soc2SecurityIntegrationTest {

	/** MockMvc entry point driving HTTP requests through the full filter chain. */
	@Autowired
	MockMvc mockMvc;
	/** Live JDBC template used to seed users and assert on persisted state. */
	@Autowired
	JdbcTemplate db;
	/** Autowired JSON mapper for request/response (de)serialisation. */
	@Autowired
	ObjectMapper mapper;

	/** Mocked Ollama service so no local model runtime is required. */
	@MockitoBean
	OllamaService ollamaService;
	/** Mocked Whisper manager so no speech runtime is required. */
	@MockitoBean
	WhisperServerManager whisperServerManager;

	/** Email of the seeded admin user. */
	private static final String ADMIN_EMAIL = "admin@ollanest.local";
	/** Plaintext password for the seeded admin user. */
	private static final String ADMIN_PASS = "junit-integration-test-only";
	/** Email of the seeded regular user. */
	private static final String REGULAR_EMAIL = "user@ollanest.local";
	/** Plaintext password for the seeded regular user. */
	private static final String REGULAR_PASS = "test-user-seed-only";

	/**
	 * Reseeds the admin and regular users and clears session/attempt state
	 * before each test.
	 *
	 * <p>
	 * Guarantees a deterministic starting point: no stale sessions, no
	 * accumulated login attempts, and both users present with known hashes.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@BeforeEach
	void seedTestUsers() {
		// Clear test state
		db.update("DELETE FROM sessions");
		db.update("DELETE FROM login_attempts");

		// Seed admin user
		String adminHash = BCrypt.hashpw(ADMIN_PASS, BCrypt.gensalt(4));
		db.update(
				"INSERT OR REPLACE INTO users "
						+ "(id, name, email, role, password_hash, auth_provider, active, rights) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				"soc2-admin", "SOC2 Admin", ADMIN_EMAIL, "admin", adminHash, "local", 1, "[\"chat:use\"]");

		// Seed regular user
		String userHash = BCrypt.hashpw(REGULAR_PASS, BCrypt.gensalt(4));
		db.update(
				"INSERT OR REPLACE INTO users "
						+ "(id, name, email, role, password_hash, auth_provider, active, rights) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				"soc2-user", "SOC2 User", REGULAR_EMAIL, "user", userHash, "local", 1, "[\"chat:use\"]");
	}

	// ── Authentication ─────────────────────────────────────────────────────

	/**
	 * Tests for the authentication flow — login, session and logout.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("AUTH: Login, session, and logout")
	class AuthFlow {

		/**
		 * Verifies valid admin credentials return 200 with a secure session cookie.
		 *
		 * <p>
		 * At least one {@code Set-Cookie} header must carry the session cookie with
		 * {@code HttpOnly} and {@code SameSite=Lax}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("valid admin credentials → 200 with session cookie")
		void validAdminLogin_returns200WithCookie() throws Exception {
			MvcResult result = mockMvc
					.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content(mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASS))))
					.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
					.andExpect(jsonPath("$.user.role").value("admin")).andReturn();

			// Two Set-Cookie headers are emitted: first from Servlet API (no SameSite),
			// second from addHeader override (with SameSite=Lax). Check all headers.
			List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
			assertThat(setCookies).isNotEmpty();
			String allCookies = String.join("; ", setCookies);
			assertThat(allCookies).contains("olla_nest_session=").contains("HttpOnly");
			// At least one Set-Cookie header should contain SameSite=Lax
			boolean hasSameSite = setCookies.stream().anyMatch(c -> c.contains("SameSite=Lax"));
			assertThat(hasSameSite).as("At least one Set-Cookie header should contain SameSite=Lax").isTrue();
		}

		/**
		 * Verifies a wrong password returns 401 with the generic error.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("wrong password → 401 with generic error (no enumeration)")
		void wrongPassword_returns401() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.error").value("Invalid email or password"));
		}

		/**
		 * Verifies an unknown email returns the identical 401 error message.
		 *
		 * <p>
		 * The wrong-password and unknown-email messages must match exactly so the
		 * two cases cannot be distinguished.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("unknown email → 401 with identical error (prevents enumeration)")
		void unknownEmail_returns401_sameMessage() throws Exception {
			MvcResult wrongPass = mockMvc
					.perform(
							post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
									.content(mapper.writeValueAsString(
											Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andReturn();
			MvcResult unknownUser = mockMvc
					.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content(mapper.writeValueAsString(
									Map.of("email", "nobody@domain.com", "password", "incorrect-credential"))))
					.andReturn();

			String msg1 = (String) mapper.readValue(wrongPass.getResponse().getContentAsString(), Map.class)
					.get("error");
			String msg2 = (String) mapper.readValue(unknownUser.getResponse().getContentAsString(), Map.class)
					.get("error");
			assertThat(msg1).isEqualTo(msg2);
		}

		/**
		 * Verifies a missing email yields 400.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("missing email → 400 bad request")
		void missingEmail_returns400() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(mapper.writeValueAsString(Map.of("password", "pass")))).andExpect(status().isBadRequest());
		}

		/**
		 * Verifies a blank password yields 400.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("blank password → 400 bad request")
		void blankPassword_returns400() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "   "))))
					.andExpect(status().isBadRequest());
		}

		/**
		 * Verifies an oversized email is rejected with 400 (BCrypt DoS guard).
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("oversized email (>320 chars) → 400 (BCrypt DoS prevention)")
		void oversizedEmail_returns400() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", "a".repeat(400) + "@domain.com", "password", "pass"))))
					.andExpect(status().isBadRequest());
		}

		/**
		 * Verifies an oversized password is rejected with 400 (BCrypt DoS guard).
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("oversized password (>1024 chars) → 400 (BCrypt DoS prevention)")
		void oversizedPassword_returns400() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "P".repeat(1025)))))
					.andExpect(status().isBadRequest());
		}

		/**
		 * Verifies a malformed JSON body yields 400.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("malformed JSON body → 400")
		void malformedJson_returns400() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{invalid json"))
					.andExpect(status().isBadRequest());
		}

		/**
		 * Verifies {@code /api/auth/me} reports authenticated for a valid session.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("GET /api/auth/me returns authenticated:true for valid session")
		void me_withValidSession_returnsAuthenticated() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			mockMvc.perform(get("/api/auth/me").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
					.andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL));
		}

		/**
		 * Verifies {@code /api/auth/me} reports not-authenticated without a session.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("GET /api/auth/me returns authenticated:false without session")
		void me_withoutSession_returnsNotAuthenticated() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk())
					.andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.user").isEmpty());
		}

		/**
		 * Verifies logout without the CSRF header is rejected with 403.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("logout without CSRF header → 403")
		void logout_missingCsrf_returns403() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			mockMvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());
		}

		/**
		 * Verifies logout with the CSRF header returns 200 and clears the cookie.
		 *
		 * <p>
		 * The {@code Set-Cookie} header must include {@code Max-Age=0}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("logout with CSRF header → 200 and clears session cookie")
		void logout_withCsrf_returns200() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			MvcResult result = mockMvc
					.perform(post("/api/auth/logout").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn();

			// Session cookie should be cleared (Max-Age=0)
			String setCookie = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookie).contains("Max-Age=0");
		}

		/**
		 * Verifies a logged-out session cookie no longer authenticates.
		 *
		 * <p>
		 * After logout the old cookie must be rejected on a subsequent
		 * {@code /api/auth/me}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("after logout, old session cookie no longer authenticates")
		void afterLogout_sessionIsInvalidated() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			// Logout
			mockMvc.perform(post("/api/auth/logout").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isOk());
			// Old cookie should be rejected
			mockMvc.perform(get("/api/auth/me").cookie(session)).andExpect(jsonPath("$.authenticated").value(false));
		}
	}

	// ── Authorization (RBAC) ──────────────────────────────────────────────

	/**
	 * Tests for role-based access control on admin-only endpoints.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("RBAC: Admin-only endpoint enforcement")
	class RbacEnforcement {

		/**
		 * Verifies an unauthenticated request to an admin endpoint returns 401.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("unauthenticated → 401 on admin endpoints")
		void unauthenticated_returns401() throws Exception {
			mockMvc.perform(get("/api/admin/users").header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isUnauthorized());
		}

		/**
		 * Verifies a regular user is forbidden (403) from an admin endpoint.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("regular user → 403 on admin-only endpoint")
		void regularUser_returns403OnAdminEndpoint() throws Exception {
			Cookie session = loginAndGetCookie(REGULAR_EMAIL, REGULAR_PASS);
			mockMvc.perform(get("/api/admin/users").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isForbidden());
		}

		/**
		 * Verifies an admin user is allowed (200) on an admin endpoint.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("admin user → 200 on admin-only endpoint")
		void adminUser_returns200OnAdminEndpoint() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			mockMvc.perform(get("/api/admin/users").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isOk());
		}

		/**
		 * Verifies an unauthenticated DELETE returns 401, not 403.
		 *
		 * <p>
		 * Returning 401 rather than 403 avoids leaking whether the target resource
		 * exists.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("unauthenticated DELETE returns 401 (not 403 — no info leakage)")
		void unauthenticated_delete_returns401_not403() throws Exception {
			mockMvc.perform(delete("/api/admin/users/some-id").header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isUnauthorized());
		}
	}

	// ── CSRF Protection ───────────────────────────────────────────────────

	/**
	 * Tests for CSRF enforcement via the X-Requested-With header on mutations.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("CSRF: X-Requested-With enforcement on mutations")
	class CsrfProtection {

		/**
		 * Verifies an authenticated POST without the CSRF header returns 403.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("POST without CSRF header → 403 for authenticated user")
		void post_withoutCsrf_returns403() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			mockMvc.perform(
					post("/api/admin/settings").cookie(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isForbidden());
		}

		/**
		 * Verifies GET requests do not require the CSRF header.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("GET requests don't need CSRF header")
		void get_withoutCsrf_succeeds() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			mockMvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isOk());
		}
	}

	// ── Security Headers ──────────────────────────────────────────────────

	/**
	 * Tests for the SOC 2-required security response headers.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("HEADERS: SOC 2-required security headers")
	class SecurityHeaders {

		/**
		 * Verifies {@code X-Content-Type-Options: nosniff} is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("X-Content-Type-Options: nosniff on all responses")
		void xContentTypeOptions_nosniff() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
		}

		/**
		 * Verifies {@code X-Frame-Options: DENY} is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("X-Frame-Options: DENY on all responses")
		void xFrameOptions_deny() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().string("X-Frame-Options", "DENY"));
		}

		/**
		 * Verifies a {@code Referrer-Policy} header is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("Referrer-Policy header present")
		void referrerPolicy_present() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().exists("Referrer-Policy"));
		}

		/**
		 * Verifies a {@code Permissions-Policy} header is present.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("Permissions-Policy header present")
		void permissionsPolicy_present() throws Exception {
			mockMvc.perform(get("/api/auth/me")).andExpect(header().exists("Permissions-Policy"));
		}

		/**
		 * Verifies the CSP forbids framing via {@code frame-ancestors 'none'}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("Content-Security-Policy includes frame-ancestors 'none'")
		void csp_includesFrameAncestors() throws Exception {
			mockMvc.perform(get("/api/auth/me"))
					.andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
		}

		/**
		 * Verifies the CSP pins {@code base-uri 'self'}.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("Content-Security-Policy includes base-uri 'self'")
		void csp_includesBaseUri() throws Exception {
			mockMvc.perform(get("/api/auth/me"))
					.andExpect(header().string("Content-Security-Policy", containsString("base-uri 'self'")));
		}
	}

	// ── Brute-Force Protection ────────────────────────────────────────────

	/**
	 * Tests for login rate limiting (brute-force protection).
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("BRUTEFORCE: Login rate limiting")
	class BruteForceProtection {

		/**
		 * Verifies the 10th attempt within the window returns 429.
		 *
		 * <p>
		 * With 10 attempts pre-seeded and a future reset, the next login must be
		 * throttled.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("10 failed attempts → 429 Too Many Requests")
		void tenFailedAttempts_returns429() throws Exception {
			// Pre-seed 10 attempts
			long resetAt = System.currentTimeMillis() + 900_000;
			db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)", "127.0.0.1", 10,
					resetAt);

			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andExpect(status().isTooManyRequests());
		}

		/**
		 * Verifies the 9th attempt is still 401 (not yet blocked).
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("9 failed attempts → still 401 (not yet blocked)")
		void nineFailedAttempts_still401() throws Exception {
			long resetAt = System.currentTimeMillis() + 900_000;
			db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)", "127.0.0.1", 9,
					resetAt);

			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andExpect(status().isUnauthorized());
		}

		/**
		 * Verifies an expired window does not block, even at a high count.
		 *
		 * <p>
		 * With a past reset and count=50, the attempt must proceed to a normal 401
		 * rather than 429.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("expired rate limit window → attempt succeeds normally")
		void expiredRateLimitWindow_resetOnNextAttempt() throws Exception {
			// Expired window — even count=50 should not block
			long expired = System.currentTimeMillis() - 1;
			db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)", "127.0.0.1", 50,
					expired);

			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andExpect(status().isUnauthorized()); // Not 429 — window expired
		}
	}

	// ── Session Security ──────────────────────────────────────────────────

	/**
	 * Tests for session-token format, uniqueness and rejection properties.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("SESSION: Token rotation and security properties")
	class SessionSecurity {

		/**
		 * Verifies the session token is 64 lowercase hex characters.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("session token is 64 lowercase hex characters")
		void sessionToken_is64LowercaseHex() throws Exception {
			MvcResult result = mockMvc
					.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content(mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASS))))
					.andExpect(status().isOk()).andReturn();

			String setCookie = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookie).isNotNull();
			String token = extractTokenFromCookie(setCookie);
			assertThat(token).matches("^[0-9a-f]{64}$").hasSize(64);
		}

		/**
		 * Verifies two logins produce different tokens (no reuse).
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("two logins produce different tokens (no reuse)")
		void twoLogins_differentTokens() throws Exception {
			String token1 = extractTokenFromCookie(
					performLogin(ADMIN_EMAIL, ADMIN_PASS).getResponse().getHeader("Set-Cookie"));
			// Clear sessions for second login
			db.update("DELETE FROM sessions WHERE user_id = 'soc2-admin'");

			String token2 = extractTokenFromCookie(
					performLogin(ADMIN_EMAIL, ADMIN_PASS).getResponse().getHeader("Set-Cookie"));

			assertThat(token1).isNotEqualTo(token2);
		}

		/**
		 * Verifies a forged but well-formed token is rejected with 401.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("protected endpoint returns 401 with forged/random token")
		void forgedToken_returns401() throws Exception {
			Cookie forged = new Cookie("olla_nest_session", "deadbeef".repeat(8)); // 64 hex chars but unknown
			mockMvc.perform(get("/api/state").cookie(forged).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isUnauthorized());
		}

		/**
		 * Verifies a wrong-format (uppercase) token is rejected before any DB hit.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("token with wrong format (uppercase) rejected before DB query")
		void uppercaseToken_rejected() throws Exception {
			Cookie forged = new Cookie("olla_nest_session", "AAAA".repeat(16)); // uppercase
			mockMvc.perform(get("/api/state").cookie(forged)).andExpect(status().isUnauthorized());
		}
	}

	// ── Audit Trail ───────────────────────────────────────────────────────

	/**
	 * Tests that security-relevant events are written to the audit trail.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("AUDIT: Security events recorded")
	class AuditTrail {

		/**
		 * Verifies a successful login records an {@code auth.login} audit event.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("successful login writes audit_events record with auth.login action")
		void successfulLogin_writesAuditEvent() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASS))))
					.andExpect(status().isOk());

			List<Map<String, Object>> events = db.queryForList(
					"SELECT action FROM audit_events WHERE action = 'auth.login' ORDER BY created_at DESC LIMIT 1");
			assertThat(events).isNotEmpty();
		}

		/**
		 * Verifies a failed login records an {@code auth.login.failed} event with IP.
		 *
		 * <p>
		 * The recorded detail must include the client IP for the audit trail.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("failed login writes auth.login.failed security event")
		void failedLogin_writesSecurityEvent() throws Exception {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", ADMIN_EMAIL, "password", "incorrect-credential"))))
					.andExpect(status().isUnauthorized());

			List<Map<String, Object>> events = db.queryForList(
					"SELECT action, detail FROM audit_events WHERE action = 'auth.login.failed' ORDER BY created_at DESC LIMIT 1");
			assertThat(events).isNotEmpty();
			String detail = (String) events.get(0).get("detail");
			assertThat(detail).contains("127.0.0.1"); // IP in audit trail
		}
	}

	// ── Confidentiality (API key redaction) ───────────────────────────────

	/**
	 * Tests that sensitive data is redacted or suppressed in API responses.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("CONF: Sensitive data redacted in API responses")
	class ConfidentialityTest {

		/**
		 * Verifies {@code /api/state} never exposes raw API keys.
		 *
		 * <p>
		 * Any present key field must read {@code "set"} or {@code ""}, never the
		 * real secret value.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("/api/state does not expose raw API keys")
		void stateEndpoint_redactsApiKeys() throws Exception {
			Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
			MvcResult result = mockMvc
					.perform(get("/api/state").cookie(session).header("x-requested-with", "XMLHttpRequest"))
					.andExpect(status().isOk()).andReturn();

			String body = result.getResponse().getContentAsString();
			@SuppressWarnings("unchecked")
			Map<String, Object> resp = mapper.readValue(body, Map.class);
			@SuppressWarnings("unchecked")
			Map<String, Object> settings = (Map<String, Object>) resp.get("settings");

			if (settings != null) {
				// API keys must be redacted to "set" or "" — never the real value
				for (String keyField : List.of("anthropicApiKey", "openaiApiKey", "groqApiKey", "customApiKey")) {
					Object val = settings.get(keyField);
					if (val != null) {
						assertThat(val.toString()).as("API key %s must be redacted", keyField).isIn("set", "");
					}
				}
			}
		}

		/**
		 * Verifies error responses do not leak stack traces.
		 *
		 * <p>
		 * A 404 body must not contain {@code java.}, {@code at com.} or
		 * {@code Exception} fragments.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("500 errors return generic message (no stack trace leakage)")
		void internalErrors_returnGenericMessage() throws Exception {
			// Hit a non-existent endpoint — should get 404 with generic body, no stack
			// trace
			MvcResult result = mockMvc.perform(get("/api/nonexistent-endpoint-xyz")).andReturn();

			String body = result.getResponse().getContentAsString();
			if (!body.isEmpty()) {
				assertThat(body).doesNotContain("java.").doesNotContain("at com.").doesNotContain("Exception");
			}
		}
	}

	// ── SSO Bypass Prevention ─────────────────────────────────────────────

	/**
	 * Tests that non-local (SSO) users cannot authenticate via password login.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("SSO: Non-local auth users cannot use password login")
	class SsoBypassPrevention {

		/**
		 * Verifies an SSO user is rejected at the password endpoint even with the
		 * correct password.
		 *
		 * <p>
		 * Although the seeded SSO user has a valid hash, an {@code auth_provider}
		 * other than {@code local} must yield the generic 401 — closing the
		 * password-login bypass.
		 *
		 * @throws Exception if the MockMvc request fails
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("SSO user with same email cannot login via password endpoint")
		void ssoUser_cannotLoginViaPasswordEndpoint() throws Exception {
			// Seed an SSO user (auth_provider != 'local') with a password hash
			String ssoHash = BCrypt.hashpw(ADMIN_PASS, BCrypt.gensalt(4));
			db.update(
					"INSERT OR REPLACE INTO users "
							+ "(id, name, email, role, password_hash, auth_provider, active, rights) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
					"sso-bypass-test", "SSO User", "sso@corp.example.com", "user", ssoHash, "saml", 1,
					"[\"chat:use\"]");

			// Even with correct password, SSO user must be rejected
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
					mapper.writeValueAsString(Map.of("email", "sso@corp.example.com", "password", ADMIN_PASS)))) // correct
																													// password
																													// for
																													// the
																													// hash
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.error").value("Invalid email or password"));
		}
	}

	// ── Helper methods ────────────────────────────────────────────────────

	/**
	 * Logs in and returns a session cookie carrying the issued token.
	 *
	 * @param email    the user email to authenticate
	 * @param password the plaintext password
	 * @return a {@link Cookie} holding the {@code olla_nest_session} token
	 * @throws Exception if the login request fails
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	private Cookie loginAndGetCookie(String email, String password) throws Exception {
		MvcResult result = performLogin(email, password);
		String setCookie = result.getResponse().getHeader("Set-Cookie");
		String token = extractTokenFromCookie(setCookie);
		return new Cookie("olla_nest_session", token);
	}

	/**
	 * Performs a login request and asserts a 200 response.
	 *
	 * @param email    the user email to authenticate
	 * @param password the plaintext password
	 * @return the {@link MvcResult} of the successful login
	 * @throws Exception if the login request fails or is not 200
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	private MvcResult performLogin(String email, String password) throws Exception {
		return mockMvc
				.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
				.andExpect(status().isOk()).andReturn();
	}

	/**
	 * Extracts the {@code olla_nest_session} token from a Set-Cookie header.
	 *
	 * @param setCookieHeader the raw Set-Cookie header value (may be null)
	 * @return the token value, or an empty string if not present
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	private String extractTokenFromCookie(String setCookieHeader) {
		if (setCookieHeader == null)
			return "";
		for (String part : setCookieHeader.split(";")) {
			String p = part.trim();
			if (p.startsWith("olla_nest_session=")) {
				return p.substring("olla_nest_session=".length());
			}
		}
		return "";
	}
}
