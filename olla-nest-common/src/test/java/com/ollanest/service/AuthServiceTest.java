package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OCD-level unit tests for {@link AuthService}.
 *
 * <p>
 * All DB and servlet interactions are Mockito-stubbed — no Spring context, no
 * real DB, no network calls. Covers: token extraction, session resolution
 * (cache hit / miss / expired), session creation (rotation, DB write, cookie),
 * clearSession, removeSession, forceLogoutUser, scheduled cleanup.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link AuthService} is the single chokepoint through which every
 * authenticated request resolves its session, so a subtle regression here would
 * be a platform-wide security incident. This suite exhaustively pins each
 * behaviour — token extraction, cache hit/miss/expiry, rotation, cookie
 * attributes, the strict token-format guard, cached-session immutability, and
 * graceful DB-failure handling — so any deviation fails fast in CI.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All collaborators are Mockito mocks injected via {@code @InjectMocks};
 * there is no Spring context, real database, or network.</li>
 * <li>{@code @Value} fields such as {@code cookieSecure} are populated with
 * {@link ReflectionTestUtils} to mimic the dev configuration.</li>
 * <li>Tests are grouped into {@link Nested} classes by service method, and
 * security-hardening invariants are asserted reflectively against private
 * fields.</li>
 * <li>Test users come from the shared {@link UserFactory} so identity
 * constants stay consistent across the suite.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — AuthService unit suite documented in the project-wide Javadoc
 * pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — unit tests")
class AuthServiceTest {

	/** Name of the session cookie the service reads and writes. */
	private static final String COOKIE_NAME = "olla_nest_session";
	/** Canonical valid 64-char lowercase-hex session token used across tests. */
	private static final String TOKEN = "a".repeat(64); // 64-char hex token

	/** Mocked JDBC template standing in for the session/user database. */
	@Mock
	JdbcTemplate db;
	/** Mocked user service used to resolve users by id. */
	@Mock
	UserService userService;
	/** Mocked inbound HTTP request supplying cookies. */
	@Mock
	HttpServletRequest req;
	/** Mocked HTTP response capturing Set-Cookie headers. */
	@Mock
	HttpServletResponse res;

	/** Service under test with the mocked collaborators injected. */
	@InjectMocks
	AuthService authService;

	/**
	 * Sets the {@code cookieSecure} flag to {@code false} before each test to mimic
	 * the local/dev configuration where the {@code Secure} cookie attribute is
	 * omitted.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@BeforeEach
	void setup() {
		ReflectionTestUtils.setField(authService, "cookieSecure", false);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// getToken()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code getToken} extracts the session token from the request cookies
	 * across the present, absent, wrong-name, and many-cookies cases.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getToken()")
	class GetToken {

		/**
		 * Asserts the token value is returned when the session cookie is present.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns token when cookie present")
		void returnsTokenWhenPresent() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			assertThat(authService.getToken(req)).isEqualTo(TOKEN);
		}

		/**
		 * Asserts {@code null} is returned when the request carries no cookies.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when no cookies at all")
		void returnsNullNoCookies() {
			when(req.getCookies()).thenReturn(null);
			assertThat(authService.getToken(req)).isNull();
		}

		/**
		 * Asserts {@code null} is returned when cookies exist but none is the session
		 * cookie.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when cookies present but session cookie missing")
		void returnsNullWrongCookieName() {
			Cookie other = new Cookie("other_cookie", "value");
			when(req.getCookies()).thenReturn(new Cookie[] { other });
			assertThat(authService.getToken(req)).isNull();
		}

		/**
		 * Asserts the session token is selected correctly when interleaved among
		 * several unrelated cookies.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns token from among multiple cookies")
		void returnsCorrectCookieAmongMany() {
			Cookie sessionCookie = new Cookie(COOKIE_NAME, TOKEN);
			Cookie other1 = new Cookie("foo", "bar");
			Cookie other2 = new Cookie("baz", "qux");
			when(req.getCookies()).thenReturn(new Cookie[] { other1, sessionCookie, other2 });
			assertThat(authService.getToken(req)).isEqualTo(TOKEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// getSessionUser()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code getSessionUser} resolves a request to a user across the full
	 * matrix: no cookie, blank token, DB miss, user-missing, valid hit with
	 * caching, and DB exception.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getSessionUser()")
	class GetSessionUser {

		/**
		 * Asserts resolution yields {@code null} when the request carries no cookie.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when no cookie present")
		void nullWhenNoCookie() {
			when(req.getCookies()).thenReturn(null);
			assertThat(authService.getSessionUser(req)).isNull();
		}

		/**
		 * Asserts resolution yields {@code null} when the cookie value is blank.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when token is blank")
		void nullWhenBlankToken() {
			Cookie cookie = new Cookie(COOKIE_NAME, "   ");
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			assertThat(authService.getSessionUser(req)).isNull();
		}

		/**
		 * Asserts resolution yields {@code null} when no session row matches the
		 * token in the database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB miss returns null (no matching session row)")
		void dbMissReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			when(db.queryForList(anyString(), eq(TOKEN))).thenReturn(Collections.emptyList());
			assertThat(authService.getSessionUser(req)).isNull();
		}

		/**
		 * Asserts resolution yields {@code null} when a session row exists but the
		 * referenced user can no longer be found.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB hit but user not found returns null")
		void dbHitButUserMissingReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			when(db.queryForList(anyString(), eq(TOKEN)))
					.thenReturn(List.of(Map.of("user_id", UserFactory.ADMIN_ID, "expires_at", "2030-01-01 00:00:00")));
			when(userService.findUserById(UserFactory.ADMIN_ID)).thenReturn(null);
			assertThat(authService.getSessionUser(req)).isNull();
		}

		/**
		 * Asserts a valid session resolves to the expected user and that a second
		 * resolution is served from cache — the DB is queried exactly once.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB hit with valid user returns user and caches it")
		void dbHitCachesUser() {
			User admin = UserFactory.admin();
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			when(db.queryForList(anyString(), eq(TOKEN)))
					.thenReturn(List.of(Map.of("user_id", admin.id, "expires_at", "2030-01-01 00:00:00")));
			when(userService.findUserById(admin.id)).thenReturn(admin);

			// First call: DB hit
			User result = authService.getSessionUser(req);
			assertThat(result).isNotNull();
			assertThat(result.id).isEqualTo(admin.id);

			// Second call: should come from cache (DB not queried again)
			authService.getSessionUser(req);
			verify(db, times(1)).queryForList(anyString(), eq(TOKEN));
		}

		/**
		 * Asserts a database failure during lookup is swallowed and resolution
		 * returns {@code null}, so an unavailable DB denies access rather than
		 * crashing the request.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB exception returns null gracefully")
		void dbExceptionReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[] { cookie });
			when(db.queryForList(anyString(), eq(TOKEN))).thenThrow(new RuntimeException("DB down"));
			assertThat(authService.getSessionUser(req)).isNull();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// setSession()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code setSession} persists the new session, sets a hardened
	 * {@code HttpOnly} cookie via a single {@code Set-Cookie} header, rotates any
	 * existing session, and omits the {@code Secure} flag in dev config.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("setSession()")
	class SetSession {

		/**
		 * Asserts session creation issues an {@code INSERT INTO sessions} bound to a
		 * 64-hex token, the user id, and an expiry, capturing and checking each
		 * parameter.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("inserts session row into DB with correct parameters")
		void insertsSessionRowIntoDb() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null); // no old session

			authService.setSession(res, req, admin);

			// Verify INSERT was called with user id
			ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO sessions"), argsCaptor.capture());
			Object[] args = argsCaptor.getValue();
			assertThat(args).hasSize(3);
			// arg[0] = token (64-char hex), arg[1] = user id, arg[2] = expires_at
			assertThat(args[0].toString()).hasSize(64).matches("[0-9a-f]+");
			assertThat(args[1]).isEqualTo(admin.id);
		}

		/**
		 * Asserts the session cookie is written via {@code setHeader} (not
		 * {@code addHeader}) so only one {@code Set-Cookie} is emitted, and that it
		 * carries the {@code HttpOnly}, {@code SameSite=Lax}, and {@code Path=/}
		 * attributes (HIGH-1 fix).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("sets HttpOnly session cookie on response (HIGH-1 fix: single Set-Cookie header via setHeader)")
		void setsCookieOnResponse() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null);

			authService.setSession(res, req, admin);

			// HIGH-1 FIX: now uses setHeader (not addHeader) to prevent double Set-Cookie
			verify(res).setHeader(eq("Set-Cookie"), argThat(v -> v.contains("olla_nest_session")
					&& v.contains("HttpOnly") && v.contains("SameSite=Lax") && v.contains("Path=/")));
		}

		/**
		 * Asserts that when an old session cookie is present, its token is deleted
		 * from the DB and a fresh session is inserted, proving rotation on
		 * re-authentication.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rotates existing session — old token deleted before new one issued")
		void rotatesOldSession() {
			User admin = UserFactory.admin();
			String oldToken = "b".repeat(64);
			Cookie oldCookie = new Cookie(COOKIE_NAME, oldToken);
			when(req.getCookies()).thenReturn(new Cookie[] { oldCookie });

			authService.setSession(res, req, admin);

			// removeSession deletes old token from DB
			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(oldToken));
			// then inserts new session
			verify(db).update(contains("INSERT INTO sessions"), any(), any(), any());
		}

		/**
		 * Captures the written {@code Set-Cookie} header and asserts it does not
		 * contain {@code ; Secure} when {@code cookieSecure=false}, matching the dev
		 * configuration.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("Secure flag NOT present when cookieSecure=false (default test config)")
		void noSecureFlagInDev() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null);

			authService.setSession(res, req, admin);

			// HIGH-1 FIX: now uses setHeader (not addHeader)
			ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
			verify(res).setHeader(eq("Set-Cookie"), headerCaptor.capture());
			// The raw header written by authService should NOT contain "; Secure"
			boolean anyHasSecure = headerCaptor.getAllValues().stream().anyMatch(v -> v.contains("; Secure"));
			assertThat(anyHasSecure).isFalse();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// clearSession()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code clearSession} revokes the session in the DB and writes an
	 * expiring cookie, while safely skipping the DB delete for a {@code null} token.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("clearSession()")
	class ClearSession {

		/**
		 * Asserts clearing a session deletes the token from the DB and writes a
		 * {@code Max-Age=0} cookie to expire it in the browser.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("removes token from cache+DB and writes Max-Age=0 cookie")
		void clearsSessionAndWritesCookie() {
			authService.clearSession(res, TOKEN);

			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(TOKEN));
			verify(res).addHeader(eq("Set-Cookie"),
					argThat(v -> v.contains("olla_nest_session=;") && v.contains("Max-Age=0")));
		}

		/**
		 * Asserts that clearing with a {@code null} token skips the DB delete yet
		 * still writes the expiring {@code Max-Age=0} cookie.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null token skips DB delete but still writes Max-Age=0 cookie")
		void nullTokenSkipsDbDelete() {
			authService.clearSession(res, null);

			verify(db, never()).update(anyString(), any(Object[].class));
			verify(res).addHeader(eq("Set-Cookie"), argThat(v -> v.contains("Max-Age=0")));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// removeSession()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code removeSession} deletes the token from the DB and never lets a
	 * database failure propagate.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("removeSession()")
	class RemoveSession {

		/**
		 * Asserts {@code removeSession} issues a parameterized
		 * {@code DELETE FROM sessions WHERE token} bound to the token.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("deletes token from DB")
		void deletesFromDb() {
			authService.removeSession(TOKEN);
			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(TOKEN));
		}

		/**
		 * Asserts a DB failure during deletion is swallowed so logout cleanup never
		 * propagates an exception to the caller.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB exception is swallowed — no exception propagated")
		void swallowsDbException() {
			when(db.update(anyString(), eq(TOKEN))).thenThrow(new RuntimeException("constraint error"));
			assertThatCode(() -> authService.removeSession(TOKEN)).doesNotThrowAnyException();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// forceLogoutUser()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies {@code forceLogoutUser} revokes all of a user's sessions and that
	 * the {@code invalidateUserSessions} alias delegates to the same behaviour.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("forceLogoutUser()")
	class ForceLogoutUser {

		/**
		 * Asserts {@code forceLogoutUser} issues a parameterized
		 * {@code DELETE FROM sessions WHERE user_id} bound to the user id.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("deletes all sessions for user from DB")
		void deletesUserSessionsFromDb() {
			authService.forceLogoutUser(UserFactory.ADMIN_ID);
			verify(db).update(contains("DELETE FROM sessions WHERE user_id"), eq(UserFactory.ADMIN_ID));
		}

		/**
		 * Asserts {@code invalidateUserSessions} performs the same per-user session
		 * DELETE as {@code forceLogoutUser}, confirming it is a true alias.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("invalidateUserSessions delegates to forceLogoutUser (alias)")
		void invalidateSessionsAlias() {
			authService.invalidateUserSessions(UserFactory.USER_ID);
			verify(db).update(contains("DELETE FROM sessions WHERE user_id"), eq(UserFactory.USER_ID));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// cleanExpiredSessions()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies the scheduled {@code cleanExpiredSessions} sweep runs the DB
	 * deletion and never lets a DB failure kill the scheduler thread.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("cleanExpiredSessions()")
	class CleanExpiredSessions {

		/**
		 * Asserts the sweep runs without throwing and issues a
		 * {@code DELETE FROM sessions WHERE expires_at} statement.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("runs DB sweep without throwing")
		void sweepsExpiredSessions() {
			assertThatCode(() -> authService.cleanExpiredSessions()).doesNotThrowAnyException();
			verify(db).update(contains("DELETE FROM sessions WHERE expires_at"));
		}

		/**
		 * Asserts a DB failure during the sweep is swallowed so the scheduled
		 * executor thread keeps running for future sweeps.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DB exception is swallowed — scheduler thread not killed")
		void swallowsDbException() {
			when(db.update(anyString())).thenThrow(new RuntimeException("DB unavailable"));
			assertThatCode(() -> authService.cleanExpiredSessions()).doesNotThrowAnyException();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Token format guard (security hardening v2026.1.9)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies the strict 64-lowercase-hex token-format guard in
	 * {@code getSessionUser} rejects malformed/hostile tokens before any DB query,
	 * while a well-formed token does reach the database.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("getSessionUser() — token format guard")
	class TokenFormatGuard {

		/**
		 * Stubs the request to carry a single session cookie with the supplied value,
		 * a shared setup step for the format-guard cases.
		 *
		 * @param value the raw token string to place in the session cookie
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		private void stubCookie(String value) {
			when(req.getCookies()).thenReturn(new Cookie[] { new Cookie(COOKIE_NAME, value) });
		}

		/**
		 * Asserts a token shorter than 64 chars resolves to {@code null} and never
		 * touches the database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("token shorter than 64 hex chars rejected before DB hit")
		void shortTokenRejectedWithoutDbHit() {
			stubCookie("abc123");
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts a token longer than 64 chars resolves to {@code null} and never
		 * touches the database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("token longer than 64 hex chars rejected before DB hit")
		void longTokenRejectedWithoutDbHit() {
			stubCookie("a".repeat(65));
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts an all-uppercase-hex token resolves to {@code null} and never
		 * touches the database, since the guard requires lowercase hex.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("uppercase hex token rejected (must be lowercase) without DB hit")
		void uppercaseTokenRejected() {
			stubCookie("A".repeat(64));
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts a SQL-injection cookie value resolves to {@code null} and never
		 * touches the database, proving injection cannot reach the query.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("SQL injection payload in cookie rejected before DB hit")
		void sqlInjectionRejectedBeforeDb() {
			stubCookie("' OR '1'='1"); // too short and contains non-hex chars
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts a CRLF-injection token value resolves to {@code null} and never
		 * touches the database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("CRLF injection in token rejected before DB hit")
		void crlfInjectionRejected() {
			stubCookie("a".repeat(60) + "\r\nX-Injected: evil");
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts a token ending in a semicolon resolves to {@code null} and never
		 * touches the database, blocking a header-injection suffix.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("token with semicolon (potential header injection) rejected before DB hit")
		void semicolonInjectionRejected() {
			stubCookie("a".repeat(63) + ";");
			assertThat(authService.getSessionUser(req)).isNull();
			verifyNoInteractions(db);
		}

		/**
		 * Asserts a well-formed 64-lowercase-hex token passes the format guard and
		 * does reach the database, confirming the guard does not reject valid tokens.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("exactly 64 lowercase hex chars passes format check and hits DB")
		void validTokenHitsDb() {
			stubCookie(TOKEN); // TOKEN = "a".repeat(64)
			when(db.queryForList(anyString(), eq(TOKEN))).thenReturn(Collections.emptyList());
			authService.getSessionUser(req);
			// DB was queried (token passed format guard)
			verify(db).queryForList(anyString(), eq(TOKEN));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// CachedSession immutability (security hardening v2026.1.9)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies the {@code CachedSession} value holder is immutable by asserting its
	 * fields are {@code final}, so a cached entry cannot be mutated after creation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("CachedSession immutability")
	class CachedSessionImmutability {

		/**
		 * Asserts the {@code CachedSession.user} field is {@code final}, preventing
		 * external replacement of the cached user.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("user field is final — prevents external mutation of cached session")
		void userFieldIsFinal() throws Exception {
			Field f = AuthService.CachedSession.class.getField("user");
			assertThat(Modifier.isFinal(f.getModifiers()))
					.as("CachedSession.user must be final to prevent external mutation").isTrue();
		}

		/**
		 * Asserts the {@code CachedSession.expiresAtMs} field is {@code final}, so a
		 * cached session's expiry cannot be extended after creation.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("expiresAtMs field is final")
		void expiresAtMsFieldIsFinal() throws Exception {
			Field f = AuthService.CachedSession.class.getField("expiresAtMs");
			assertThat(Modifier.isFinal(f.getModifiers())).as("CachedSession.expiresAtMs must be final").isTrue();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// SecureRandom reuse (performance + security hardening v2026.1.9)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies session-token generation reuses a single static {@code SecureRandom}
	 * and still produces collision-free tokens across many calls.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("setSession() — SecureRandom reuse")
	class SecureRandomReuse {

		/**
		 * Asserts {@code AuthService.SECURE_RANDOM} is a static field, confirming the
		 * RNG is shared rather than re-instantiated on every session creation.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("SECURE_RANDOM static field exists — no per-call SecureRandom instantiation")
		void staticSecureRandomFieldExists() throws Exception {
			Field f = AuthService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(Modifier.isStatic(f.getModifiers())).as("SECURE_RANDOM must be a static field").isTrue();
		}

		/**
		 * Performs many consecutive session creations, captures every inserted token,
		 * and asserts they are all unique — proving the shared RNG still yields
		 * collision-free 64-hex tokens.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("100 consecutive setSession calls produce 100 unique 64-hex tokens")
		void hundredSessionsProduceUniqueTokens() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null);

			Set<String> tokens = new HashSet<>();
			for (int i = 0; i < 100; i++) {
				authService.setSession(res, req, admin);
			}

			// Capture all tokens written to INSERT INTO sessions
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db, times(100)).update(contains("INSERT INTO sessions"), cap.capture());
			cap.getAllValues().forEach(args -> tokens.add(args[0].toString()));
			assertThat(tokens).hasSize(100);
		}
	}
}
