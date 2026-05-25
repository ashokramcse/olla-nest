package com.ollanest.service;

import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link AuthService}.
 *
 * <p>All DB and servlet interactions are Mockito-stubbed — no Spring context,
 * no real DB, no network calls. Covers: token extraction, session resolution
 * (cache hit / miss / expired), session creation (rotation, DB write, cookie),
 * clearSession, removeSession, forceLogoutUser, scheduled cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — unit tests")
class AuthServiceTest {

	private static final String COOKIE_NAME = "olla_nest_session";
	private static final String TOKEN       = "a".repeat(64); // 64-char hex token

	@Mock JdbcTemplate   db;
	@Mock UserService    userService;
	@Mock HttpServletRequest  req;
	@Mock HttpServletResponse res;

	@InjectMocks AuthService authService;

	@BeforeEach
	void setup() {
		ReflectionTestUtils.setField(authService, "cookieSecure", false);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// getToken()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getToken()")
	class GetToken {

		@Test
		@DisplayName("returns token when cookie present")
		void returnsTokenWhenPresent() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
			assertThat(authService.getToken(req)).isEqualTo(TOKEN);
		}

		@Test
		@DisplayName("returns null when no cookies at all")
		void returnsNullNoCookies() {
			when(req.getCookies()).thenReturn(null);
			assertThat(authService.getToken(req)).isNull();
		}

		@Test
		@DisplayName("returns null when cookies present but session cookie missing")
		void returnsNullWrongCookieName() {
			Cookie other = new Cookie("other_cookie", "value");
			when(req.getCookies()).thenReturn(new Cookie[]{other});
			assertThat(authService.getToken(req)).isNull();
		}

		@Test
		@DisplayName("returns token from among multiple cookies")
		void returnsCorrectCookieAmongMany() {
			Cookie sessionCookie = new Cookie(COOKIE_NAME, TOKEN);
			Cookie other1 = new Cookie("foo", "bar");
			Cookie other2 = new Cookie("baz", "qux");
			when(req.getCookies()).thenReturn(new Cookie[]{other1, sessionCookie, other2});
			assertThat(authService.getToken(req)).isEqualTo(TOKEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// getSessionUser()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getSessionUser()")
	class GetSessionUser {

		@Test
		@DisplayName("returns null when no cookie present")
		void nullWhenNoCookie() {
			when(req.getCookies()).thenReturn(null);
			assertThat(authService.getSessionUser(req)).isNull();
		}

		@Test
		@DisplayName("returns null when token is blank")
		void nullWhenBlankToken() {
			Cookie cookie = new Cookie(COOKIE_NAME, "   ");
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
			assertThat(authService.getSessionUser(req)).isNull();
		}

		@Test
		@DisplayName("DB miss returns null (no matching session row)")
		void dbMissReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
			when(db.queryForList(anyString(), eq(TOKEN))).thenReturn(Collections.emptyList());
			assertThat(authService.getSessionUser(req)).isNull();
		}

		@Test
		@DisplayName("DB hit but user not found returns null")
		void dbHitButUserMissingReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
			when(db.queryForList(anyString(), eq(TOKEN)))
					.thenReturn(List.of(Map.of("user_id", UserFactory.ADMIN_ID, "expires_at", "2030-01-01 00:00:00")));
			when(userService.findUserById(UserFactory.ADMIN_ID)).thenReturn(null);
			assertThat(authService.getSessionUser(req)).isNull();
		}

		@Test
		@DisplayName("DB hit with valid user returns user and caches it")
		void dbHitCachesUser() {
			User admin = UserFactory.admin();
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
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

		@Test
		@DisplayName("DB exception returns null gracefully")
		void dbExceptionReturnsNull() {
			Cookie cookie = new Cookie(COOKIE_NAME, TOKEN);
			when(req.getCookies()).thenReturn(new Cookie[]{cookie});
			when(db.queryForList(anyString(), eq(TOKEN))).thenThrow(new RuntimeException("DB down"));
			assertThat(authService.getSessionUser(req)).isNull();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// setSession()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("setSession()")
	class SetSession {

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

		@Test
		@DisplayName("sets HttpOnly session cookie on response")
		void setsCookieOnResponse() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null);

			authService.setSession(res, req, admin);

			verify(res, atLeastOnce()).addHeader(eq("Set-Cookie"), argThat(v ->
					v.contains("olla_nest_session")
					&& v.contains("HttpOnly")
					&& v.contains("SameSite=Lax")
					&& v.contains("Path=/")
			));
		}

		@Test
		@DisplayName("rotates existing session — old token deleted before new one issued")
		void rotatesOldSession() {
			User admin = UserFactory.admin();
			String oldToken = "b".repeat(64);
			Cookie oldCookie = new Cookie(COOKIE_NAME, oldToken);
			when(req.getCookies()).thenReturn(new Cookie[]{oldCookie});

			authService.setSession(res, req, admin);

			// removeSession deletes old token from DB
			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(oldToken));
			// then inserts new session
			verify(db).update(contains("INSERT INTO sessions"), any(), any(), any());
		}

		@Test
		@DisplayName("Secure flag NOT present when cookieSecure=false (default test config)")
		void noSecureFlagInDev() {
			User admin = UserFactory.admin();
			when(req.getCookies()).thenReturn(null);

			authService.setSession(res, req, admin);

			ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
			verify(res, atLeastOnce()).addHeader(eq("Set-Cookie"), headerCaptor.capture());
			// The raw header written by authService should NOT contain "; Secure"
			boolean anyHasSecure = headerCaptor.getAllValues().stream()
					.anyMatch(v -> v.contains("; Secure"));
			assertThat(anyHasSecure).isFalse();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// clearSession()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("clearSession()")
	class ClearSession {

		@Test
		@DisplayName("removes token from cache+DB and writes Max-Age=0 cookie")
		void clearsSessionAndWritesCookie() {
			authService.clearSession(res, TOKEN);

			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(TOKEN));
			verify(res).addHeader(eq("Set-Cookie"), argThat(v ->
					v.contains("olla_nest_session=;")
					&& v.contains("Max-Age=0")
			));
		}

		@Test
		@DisplayName("null token skips DB delete but still writes Max-Age=0 cookie")
		void nullTokenSkipsDbDelete() {
			authService.clearSession(res, null);

			verify(db, never()).update(anyString(), (Object[]) any());
			verify(res).addHeader(eq("Set-Cookie"), argThat(v -> v.contains("Max-Age=0")));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// removeSession()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("removeSession()")
	class RemoveSession {

		@Test
		@DisplayName("deletes token from DB")
		void deletesFromDb() {
			authService.removeSession(TOKEN);
			verify(db).update(contains("DELETE FROM sessions WHERE token"), eq(TOKEN));
		}

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

	@Nested
	@DisplayName("forceLogoutUser()")
	class ForceLogoutUser {

		@Test
		@DisplayName("deletes all sessions for user from DB")
		void deletesUserSessionsFromDb() {
			authService.forceLogoutUser(UserFactory.ADMIN_ID);
			verify(db).update(contains("DELETE FROM sessions WHERE user_id"), eq(UserFactory.ADMIN_ID));
		}

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

	@Nested
	@DisplayName("cleanExpiredSessions()")
	class CleanExpiredSessions {

		@Test
		@DisplayName("runs DB sweep without throwing")
		void sweepsExpiredSessions() {
			assertThatCode(() -> authService.cleanExpiredSessions()).doesNotThrowAnyException();
			verify(db).update(contains("DELETE FROM sessions WHERE expires_at"));
		}

		@Test
		@DisplayName("DB exception is swallowed — scheduler thread not killed")
		void swallowsDbException() {
			when(db.update(anyString())).thenThrow(new RuntimeException("DB unavailable"));
			assertThatCode(() -> authService.cleanExpiredSessions()).doesNotThrowAnyException();
		}
	}
}
