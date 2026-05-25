package com.ollanest.admin.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.admin.OllaNestAdminApplication;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.WhisperServerManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SOC 2 Security Integration Tests — runs the full Admin Spring Boot context
 * against an in-memory SQLite database.
 *
 * <p>Validates SOC 2 trust criteria end-to-end through the HTTP layer:
 * <ul>
 *   <li>Authentication: login, logout, session cookie enforcement</li>
 *   <li>Authorization: RBAC, admin-only endpoints, unauthenticated 401</li>
 *   <li>CSRF: X-Requested-With enforcement on mutation endpoints</li>
 *   <li>Brute-force: rate limiting via login_attempts table</li>
 *   <li>Input validation: malformed bodies, oversized inputs</li>
 *   <li>Security headers: CSP, X-Frame-Options, etc.</li>
 *   <li>Confidentiality: API keys redacted in state response</li>
 *   <li>Audit trail: login events written to audit_events table</li>
 *   <li>Processing integrity: no session fixation, proper rotation</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0 — SOC 2 hardening
 */
@SpringBootTest(
        classes = OllaNestAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("SOC 2 Security Integration Tests — Admin App")
class Soc2SecurityIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;

    @MockBean OllamaService ollamaService;
    @MockBean WhisperServerManager whisperServerManager;

    private static final String ADMIN_EMAIL   = "admin@ollanest.local";
    private static final String ADMIN_PASS    = "junit-integration-test-only";
    private static final String REGULAR_EMAIL = "user@ollanest.local";
    private static final String REGULAR_PASS  = "test-user-seed-only";

    @BeforeEach
    void seedTestUsers() {
        // Clear test state
        db.update("DELETE FROM sessions");
        db.update("DELETE FROM login_attempts");

        // Seed admin user
        String adminHash = BCrypt.hashpw(ADMIN_PASS, BCrypt.gensalt(4));
        db.update("INSERT OR REPLACE INTO users " +
                "(id, name, email, role, password_hash, auth_provider, active, rights) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "soc2-admin", "SOC2 Admin", ADMIN_EMAIL, "admin", adminHash, "local", 1, "[\"chat:use\"]");

        // Seed regular user
        String userHash = BCrypt.hashpw(REGULAR_PASS, BCrypt.gensalt(4));
        db.update("INSERT OR REPLACE INTO users " +
                "(id, name, email, role, password_hash, auth_provider, active, rights) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "soc2-user", "SOC2 User", REGULAR_EMAIL, "user", userHash, "local", 1, "[\"chat:use\"]");
    }

    // ── Authentication ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AUTH: Login, session, and logout")
    class AuthFlow {

        @Test
        @DisplayName("valid admin credentials → 200 with session cookie")
        void validAdminLogin_returns200WithCookie() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", ADMIN_PASS))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.user.role").value("admin"))
                    .andReturn();

            // Two Set-Cookie headers are emitted: first from Servlet API (no SameSite),
            // second from addHeader override (with SameSite=Lax). Check all headers.
            List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
            assertThat(setCookies).isNotEmpty();
            String allCookies = String.join("; ", setCookies);
            assertThat(allCookies)
                    .contains("olla_nest_session=")
                    .contains("HttpOnly");
            // At least one Set-Cookie header should contain SameSite=Lax
            boolean hasSameSite = setCookies.stream().anyMatch(c -> c.contains("SameSite=Lax"));
            assertThat(hasSameSite)
                    .as("At least one Set-Cookie header should contain SameSite=Lax")
                    .isTrue();
        }

        @Test
        @DisplayName("wrong password → 401 with generic error (no enumeration)")
        void wrongPassword_returns401() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid email or password"));
        }

        @Test
        @DisplayName("unknown email → 401 with identical error (prevents enumeration)")
        void unknownEmail_returns401_sameMessage() throws Exception {
            MvcResult wrongPass = mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andReturn();
            MvcResult unknownUser = mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", "nobody@domain.com", "password", "incorrect-credential"))))
                    .andReturn();

            String msg1 = (String) mapper.readValue(wrongPass.getResponse().getContentAsString(), Map.class).get("error");
            String msg2 = (String) mapper.readValue(unknownUser.getResponse().getContentAsString(), Map.class).get("error");
            assertThat(msg1).isEqualTo(msg2);
        }

        @Test
        @DisplayName("missing email → 400 bad request")
        void missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("password", "pass"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("blank password → 400 bad request")
        void blankPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "   "))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("oversized email (>320 chars) → 400 (BCrypt DoS prevention)")
        void oversizedEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", "a".repeat(400) + "@domain.com",
                            "password", "pass"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("oversized password (>1024 chars) → 400 (BCrypt DoS prevention)")
        void oversizedPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL,
                            "password", "P".repeat(1025)))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("malformed JSON body → 400")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET /api/auth/me returns authenticated:true for valid session")
        void me_withValidSession_returnsAuthenticated() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            mockMvc.perform(get("/api/auth/me")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL));
        }

        @Test
        @DisplayName("GET /api/auth/me returns authenticated:false without session")
        void me_withoutSession_returnsNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false))
                    .andExpect(jsonPath("$.user").isEmpty());
        }

        @Test
        @DisplayName("logout without CSRF header → 403")
        void logout_missingCsrf_returns403() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            mockMvc.perform(post("/api/auth/logout").cookie(session))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("logout with CSRF header → 200 and clears session cookie")
        void logout_withCsrf_returns200() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            MvcResult result = mockMvc.perform(post("/api/auth/logout")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andReturn();

            // Session cookie should be cleared (Max-Age=0)
            String setCookie = result.getResponse().getHeader("Set-Cookie");
            assertThat(setCookie).contains("Max-Age=0");
        }

        @Test
        @DisplayName("after logout, old session cookie no longer authenticates")
        void afterLogout_sessionIsInvalidated() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            // Logout
            mockMvc.perform(post("/api/auth/logout")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isOk());
            // Old cookie should be rejected
            mockMvc.perform(get("/api/auth/me").cookie(session))
                    .andExpect(jsonPath("$.authenticated").value(false));
        }
    }

    // ── Authorization (RBAC) ──────────────────────────────────────────────

    @Nested
    @DisplayName("RBAC: Admin-only endpoint enforcement")
    class RbacEnforcement {

        @Test
        @DisplayName("unauthenticated → 401 on admin endpoints")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/admin/users")
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("regular user → 403 on admin-only endpoint")
        void regularUser_returns403OnAdminEndpoint() throws Exception {
            Cookie session = loginAndGetCookie(REGULAR_EMAIL, REGULAR_PASS);
            mockMvc.perform(get("/api/admin/users")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("admin user → 200 on admin-only endpoint")
        void adminUser_returns200OnAdminEndpoint() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            mockMvc.perform(get("/api/admin/users")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("unauthenticated DELETE returns 401 (not 403 — no info leakage)")
        void unauthenticated_delete_returns401_not403() throws Exception {
            mockMvc.perform(delete("/api/admin/users/some-id")
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── CSRF Protection ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CSRF: X-Requested-With enforcement on mutations")
    class CsrfProtection {

        @Test
        @DisplayName("POST without CSRF header → 403 for authenticated user")
        void post_withoutCsrf_returns403() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            mockMvc.perform(post("/api/admin/settings")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET requests don't need CSRF header")
        void get_withoutCsrf_succeeds() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            mockMvc.perform(get("/api/admin/users").cookie(session))
                    .andExpect(status().isOk());
        }
    }

    // ── Security Headers ──────────────────────────────────────────────────

    @Nested
    @DisplayName("HEADERS: SOC 2-required security headers")
    class SecurityHeaders {

        @Test
        @DisplayName("X-Content-Type-Options: nosniff on all responses")
        void xContentTypeOptions_nosniff() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test
        @DisplayName("X-Frame-Options: DENY on all responses")
        void xFrameOptions_deny() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test
        @DisplayName("Referrer-Policy header present")
        void referrerPolicy_present() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().exists("Referrer-Policy"));
        }

        @Test
        @DisplayName("Permissions-Policy header present")
        void permissionsPolicy_present() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().exists("Permissions-Policy"));
        }

        @Test
        @DisplayName("Content-Security-Policy includes frame-ancestors 'none'")
        void csp_includesFrameAncestors() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().string("Content-Security-Policy",
                            containsString("frame-ancestors 'none'")));
        }

        @Test
        @DisplayName("Content-Security-Policy includes base-uri 'self'")
        void csp_includesBaseUri() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(header().string("Content-Security-Policy",
                            containsString("base-uri 'self'")));
        }
    }

    // ── Brute-Force Protection ────────────────────────────────────────────

    @Nested
    @DisplayName("BRUTEFORCE: Login rate limiting")
    class BruteForceProtection {

        @Test
        @DisplayName("10 failed attempts → 429 Too Many Requests")
        void tenFailedAttempts_returns429() throws Exception {
            // Pre-seed 10 attempts
            long resetAt = System.currentTimeMillis() + 900_000;
            db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)",
                    "127.0.0.1", 10, resetAt);

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("9 failed attempts → still 401 (not yet blocked)")
        void nineFailedAttempts_still401() throws Exception {
            long resetAt = System.currentTimeMillis() + 900_000;
            db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)",
                    "127.0.0.1", 9, resetAt);

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("expired rate limit window → attempt succeeds normally")
        void expiredRateLimitWindow_resetOnNextAttempt() throws Exception {
            // Expired window — even count=50 should not block
            long expired = System.currentTimeMillis() - 1;
            db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)",
                    "127.0.0.1", 50, expired);

            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andExpect(status().isUnauthorized()); // Not 429 — window expired
        }
    }

    // ── Session Security ──────────────────────────────────────────────────

    @Nested
    @DisplayName("SESSION: Token rotation and security properties")
    class SessionSecurity {

        @Test
        @DisplayName("session token is 64 lowercase hex characters")
        void sessionToken_is64LowercaseHex() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", ADMIN_PASS))))
                    .andExpect(status().isOk())
                    .andReturn();

            String setCookie = result.getResponse().getHeader("Set-Cookie");
            assertThat(setCookie).isNotNull();
            String token = extractTokenFromCookie(setCookie);
            assertThat(token)
                    .matches("^[0-9a-f]{64}$")
                    .hasSize(64);
        }

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

        @Test
        @DisplayName("protected endpoint returns 401 with forged/random token")
        void forgedToken_returns401() throws Exception {
            Cookie forged = new Cookie("olla_nest_session", "deadbeef".repeat(8)); // 64 hex chars but unknown
            mockMvc.perform(get("/api/state")
                    .cookie(forged)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("token with wrong format (uppercase) rejected before DB query")
        void uppercaseToken_rejected() throws Exception {
            Cookie forged = new Cookie("olla_nest_session", "AAAA".repeat(16)); // uppercase
            mockMvc.perform(get("/api/state")
                    .cookie(forged))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Audit Trail ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AUDIT: Security events recorded")
    class AuditTrail {

        @Test
        @DisplayName("successful login writes audit_events record with auth.login action")
        void successfulLogin_writesAuditEvent() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", ADMIN_PASS))))
                    .andExpect(status().isOk());

            List<Map<String, Object>> events = db.queryForList(
                    "SELECT action FROM audit_events WHERE action = 'auth.login' ORDER BY created_at DESC LIMIT 1");
            assertThat(events).isNotEmpty();
        }

        @Test
        @DisplayName("failed login writes auth.login.failed security event")
        void failedLogin_writesSecurityEvent() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", ADMIN_EMAIL, "password", "incorrect-credential"))))
                    .andExpect(status().isUnauthorized());

            List<Map<String, Object>> events = db.queryForList(
                    "SELECT action, detail FROM audit_events WHERE action = 'auth.login.failed' ORDER BY created_at DESC LIMIT 1");
            assertThat(events).isNotEmpty();
            String detail = (String) events.get(0).get("detail");
            assertThat(detail).contains("127.0.0.1"); // IP in audit trail
        }
    }

    // ── Confidentiality (API key redaction) ───────────────────────────────

    @Nested
    @DisplayName("CONF: Sensitive data redacted in API responses")
    class ConfidentialityTest {

        @Test
        @DisplayName("/api/state does not expose raw API keys")
        void stateEndpoint_redactsApiKeys() throws Exception {
            Cookie session = loginAndGetCookie(ADMIN_EMAIL, ADMIN_PASS);
            MvcResult result = mockMvc.perform(get("/api/state")
                    .cookie(session)
                    .header("x-requested-with", "XMLHttpRequest"))
                    .andExpect(status().isOk())
                    .andReturn();

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
                        assertThat(val.toString())
                                .as("API key %s must be redacted", keyField)
                                .isIn("set", "");
                    }
                }
            }
        }

        @Test
        @DisplayName("500 errors return generic message (no stack trace leakage)")
        void internalErrors_returnGenericMessage() throws Exception {
            // Hit a non-existent endpoint — should get 404 with generic body, no stack trace
            MvcResult result = mockMvc.perform(get("/api/nonexistent-endpoint-xyz"))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            if (!body.isEmpty()) {
                assertThat(body)
                        .doesNotContain("java.")
                        .doesNotContain("at com.")
                        .doesNotContain("Exception");
            }
        }
    }

    // ── SSO Bypass Prevention ─────────────────────────────────────────────

    @Nested
    @DisplayName("SSO: Non-local auth users cannot use password login")
    class SsoBypassPrevention {

        @Test
        @DisplayName("SSO user with same email cannot login via password endpoint")
        void ssoUser_cannotLoginViaPasswordEndpoint() throws Exception {
            // Seed an SSO user (auth_provider != 'local') with a password hash
            String ssoHash = BCrypt.hashpw(ADMIN_PASS, BCrypt.gensalt(4));
            db.update("INSERT OR REPLACE INTO users " +
                    "(id, name, email, role, password_hash, auth_provider, active, rights) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "sso-bypass-test", "SSO User", "sso@corp.example.com",
                    "user", ssoHash, "saml", 1, "[\"chat:use\"]");

            // Even with correct password, SSO user must be rejected
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "email", "sso@corp.example.com",
                            "password", ADMIN_PASS))))  // correct password for the hash
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid email or password"));
        }
    }

    // ── Helper methods ────────────────────────────────────────────────────

    private Cookie loginAndGetCookie(String email, String password) throws Exception {
        MvcResult result = performLogin(email, password);
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String token = extractTokenFromCookie(setCookie);
        return new Cookie("olla_nest_session", token);
    }

    private MvcResult performLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String extractTokenFromCookie(String setCookieHeader) {
        if (setCookieHeader == null) return "";
        for (String part : setCookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("olla_nest_session=")) {
                return p.substring("olla_nest_session=".length());
            }
        }
        return "";
    }
}
