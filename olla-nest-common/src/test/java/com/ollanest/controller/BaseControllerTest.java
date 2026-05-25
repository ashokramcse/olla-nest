package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BaseController} guards and helpers.
 *
 * <p>Uses a concrete anonymous subclass to access protected methods.
 * Covers: getUser, requireAuth, requireAdmin (auth/role/CSRF), requireAuthWithCsrf,
 * isCsrfOk, sanitizeText (XSS vectors, null, empty, whitespace).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseController — unit tests")
class BaseControllerTest {

	/** Minimal concrete subclass to exercise protected BaseController methods. */
	private static class TestableController extends BaseController {
		public User publicGetUser(HttpServletRequest req) { return getUser(req); }
		public ResponseEntity<Map<String,Object>> publicRequireAuth(HttpServletRequest req) { return requireAuth(req); }
		public ResponseEntity<Map<String,Object>> publicRequireAdmin(HttpServletRequest req) { return requireAdmin(req); }
		public ResponseEntity<Map<String,Object>> publicRequireAuthWithCsrf(HttpServletRequest req) { return requireAuthWithCsrf(req); }
		public boolean publicIsCsrfOk(HttpServletRequest req) { return isCsrfOk(req); }
		public static String publicSanitize(String input) { return sanitizeText(input); }
	}

	private final TestableController controller = new TestableController();

	@Mock HttpServletRequest req;

	// ─────────────────────────────────────────────────────────────────────────
	// getUser()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getUser()")
	class GetUser {

		@Test
		@DisplayName("returns User when authenticatedUser attribute is set")
		void returnsUserWhenAttributePresent() {
			User admin = UserFactory.admin();
			when(req.getAttribute("authenticatedUser")).thenReturn(admin);
			assertThat(controller.publicGetUser(req)).isSameAs(admin);
		}

		@Test
		@DisplayName("returns null when authenticatedUser attribute is absent")
		void returnsNullWhenNoAttribute() {
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			assertThat(controller.publicGetUser(req)).isNull();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAuth()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("requireAuth()")
	class RequireAuth {

		@Test
		@DisplayName("returns null (pass) when user is authenticated")
		void passesForAuthenticatedUser() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			assertThat(controller.publicRequireAuth(req)).isNull();
		}

		@Test
		@DisplayName("returns 401 when user is null (unauthenticated)")
		void returns401ForUnauthenticated() {
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			ResponseEntity<Map<String,Object>> result = controller.publicRequireAuth(req);
			assertThat(result).isNotNull();
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(result.getBody()).containsEntry("ok", false);
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("login");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAdmin()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("requireAdmin()")
	class RequireAdmin {

		@Test
		@DisplayName("returns null (pass) for admin GET request with no CSRF needed")
		void passesForAdminGetRequest() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicRequireAdmin(req)).isNull();
		}

		@Test
		@DisplayName("returns null (pass) for admin POST with X-Requested-With header")
		void passesForAdminPostWithCsrf() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicRequireAdmin(req)).isNull();
		}

		@Test
		@DisplayName("returns 401 when user is not authenticated")
		void returns401WhenNotAuthenticated() {
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			ResponseEntity<Map<String,Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("returns 403 when user is authenticated but not admin")
		void returns403WhenNotAdmin() {
			User regularUser = UserFactory.regularUser(); // role = "user"
			when(req.getAttribute("authenticatedUser")).thenReturn(regularUser);
			ResponseEntity<Map<String,Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(result.getBody()).containsEntry("ok", false);
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("admin");
		}

		@Test
		@DisplayName("returns 403 when admin POST is missing X-Requested-With (CSRF blocked)")
		void returns403WhenAdminPostMissingCsrf() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String,Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("CSRF");
		}

		@Test
		@DisplayName("CSRF check applies to DELETE method too")
		void returns403ForDeleteWithoutCsrf() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("DELETE");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String,Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAuthWithCsrf()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("requireAuthWithCsrf()")
	class RequireAuthWithCsrf {

		@Test
		@DisplayName("passes for any authenticated GET — no CSRF needed")
		void passesForAuthenticatedGet() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicRequireAuthWithCsrf(req)).isNull();
		}

		@Test
		@DisplayName("passes for authenticated POST with X-Requested-With")
		void passesForAuthenticatedPostWithCsrf() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicRequireAuthWithCsrf(req)).isNull();
		}

		@Test
		@DisplayName("returns 401 for unauthenticated request")
		void returns401ForUnauthenticated() {
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			assertThat(controller.publicRequireAuthWithCsrf(req).getStatusCode())
					.isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("returns 403 for authenticated POST without X-Requested-With (CSRF)")
		void returns403ForMissingCsrf() {
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			assertThat(controller.publicRequireAuthWithCsrf(req).getStatusCode())
					.isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// isCsrfOk()
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("isCsrfOk()")
	class IsCsrfOk {

		@Test
		@DisplayName("GET always passes CSRF check")
		void getAlwaysPasses() {
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicIsCsrfOk(req)).isTrue();
		}

		@Test
		@DisplayName("POST with X-Requested-With passes CSRF check")
		void postWithHeaderPasses() {
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicIsCsrfOk(req)).isTrue();
		}

		@Test
		@DisplayName("POST without X-Requested-With fails CSRF check")
		void postWithoutHeaderFails() {
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			assertThat(controller.publicIsCsrfOk(req)).isFalse();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// sanitizeText() — XSS protection
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("sanitizeText() — XSS protection")
	class SanitizeText {

		@Test
		@DisplayName("null input returns null without NPE")
		void nullInputReturnsNull() {
			assertThat(TestableController.publicSanitize(null)).isNull();
		}

		@ParameterizedTest(name = "blank input ''{0}'' returns empty string")
		@ValueSource(strings = {"", "   ", "\t", "\n"})
		void blankInputReturnsEmpty(String input) {
			assertThat(TestableController.publicSanitize(input)).isEmpty();
		}

		@Test
		@DisplayName("< and > are HTML-escaped to &lt; and &gt;")
		void escapesAngleBrackets() {
			String result = TestableController.publicSanitize("<script>alert(1)</script>");
			// Angle brackets must be escaped — no literal < or > remain
			assertThat(result).doesNotContain("<").doesNotContain(">");
			assertThat(result).contains("&lt;", "&gt;");
		}

		@Test
		@DisplayName("& is HTML-escaped to &amp;")
		void escapesAmpersand() {
			String result = TestableController.publicSanitize("AT&T");
			assertThat(result).contains("&amp;");
			assertThat(result).doesNotContain("AT&T"); // raw & gone
		}

		@Test
		@DisplayName("double quote is HTML-escaped to &quot;")
		void escapesDoubleQuote() {
			String result = TestableController.publicSanitize("say \"hello\"");
			assertThat(result).contains("&quot;");
		}

		@Test
		@DisplayName("onerror XSS vector: angle brackets escaped so no executable tag remains")
		void sanitizesOnErrorVector() {
			String input = "<img src=x onerror=alert(1)>";
			String result = TestableController.publicSanitize(input);
			// The angle brackets must be escaped — no literal <img ...> tag
			assertThat(result).doesNotContain("<img");
			assertThat(result).doesNotContain(">");
			assertThat(result).contains("&lt;img");
		}

		@Test
		@DisplayName("javascript: in href: angle brackets escaped — no active anchor tag")
		void sanitizesJavascriptProtocol() {
			String input = "<a href=\"javascript:void(0)\">click</a>";
			String result = TestableController.publicSanitize(input);
			// Must not contain literal unescaped <a ...> opening tag
			assertThat(result).doesNotContain("<a ");
			assertThat(result).doesNotContain("<a\t");
			assertThat(result).contains("&lt;a ");
		}

		@Test
		@DisplayName("clean text passes through unchanged (no unnecessary escaping)")
		void cleanTextPassesThrough() {
			String input = "Hello, World! This is a clean message.";
			assertThat(TestableController.publicSanitize(input)).isEqualTo(input);
		}

		@Test
		@DisplayName("leading/trailing whitespace is stripped")
		void stripsLeadingTrailingWhitespace() {
			assertThat(TestableController.publicSanitize("  hello  ")).isEqualTo("hello");
		}
	}
}
