package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ollanest.model.User;
import com.ollanest.testinfra.UserFactory;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link BaseController} guards and helpers.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link BaseController} provides the shared authentication, authorization and
 * CSRF guards plus the XSS sanitiser that every controller inherits. Because a
 * regression here weakens every endpoint at once, these tests pin the exact
 * status codes the guards return (401 vs 403), the CSRF requirement on mutating
 * methods, and the escaping behaviour of {@code sanitizeText} against common
 * XSS vectors.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A concrete {@link TestableController} subclass exposes the protected
 * guard/helper methods so they can be exercised directly.</li>
 * <li>The servlet request is a Mockito mock; user fixtures come from
 * {@link UserFactory}.</li>
 * <li>Nested groups map onto each guard/helper so failures localise to the
 * exact method under test.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — getUser/requireAuth/requireAdmin/requireAuthWithCsrf/
 * isCsrfOk and sanitizeText coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseController — unit tests")
class BaseControllerTest {

	/**
	 * Minimal concrete subclass to exercise protected {@link BaseController}
	 * methods from the test.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private static class TestableController extends BaseController {
		/**
		 * Exposes the protected {@code getUser} for testing.
		 *
		 * @param req the inbound request
		 * @return the authenticated user, or null
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public User publicGetUser(HttpServletRequest req) {
			return getUser(req);
		}

		/**
		 * Tests the legacy guard-style requireAuth (returns ResponseEntity or null).
		 *
		 * @param req the inbound request
		 * @return a 401 response when unauthenticated, otherwise null
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public ResponseEntity<Map<String, Object>> publicRequireAuth(HttpServletRequest req) {
			return guardAuth(req);
		}

		/**
		 * Exposes the protected {@code requireAdmin} guard for testing.
		 *
		 * @param req the inbound request
		 * @return a 401/403 response when blocked, otherwise null
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public ResponseEntity<Map<String, Object>> publicRequireAdmin(HttpServletRequest req) {
			return requireAdmin(req);
		}

		/**
		 * Exposes the protected CSRF-aware auth guard for testing.
		 *
		 * @param req the inbound request
		 * @return a 401/403 response when blocked, otherwise null
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public ResponseEntity<Map<String, Object>> publicRequireAuthWithCsrf(HttpServletRequest req) {
			return guardAuthWithCsrf(req);
		}

		/**
		 * Exposes the protected {@code isCsrfOk} predicate for testing.
		 *
		 * @param req the inbound request
		 * @return true when the request satisfies CSRF requirements
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public boolean publicIsCsrfOk(HttpServletRequest req) {
			return isCsrfOk(req);
		}

		/**
		 * Exposes the protected static {@code sanitizeText} for testing.
		 *
		 * @param input the raw input to sanitise
		 * @return the sanitised text
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		public static String publicSanitize(String input) {
			return sanitizeText(input);
		}
	}

	/** Controller under test (concrete testable subclass). */
	private final TestableController controller = new TestableController();

	/** Mocked inbound request supplying the auth attribute, method and headers. */
	@Mock
	HttpServletRequest req;

	// ─────────────────────────────────────────────────────────────────────────
	// getUser()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code getUser()} — reading the authenticated user attribute.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getUser()")
	class GetUser {

		/**
		 * Verifies the user is returned when the attribute is present.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns User when authenticatedUser attribute is set")
		void returnsUserWhenAttributePresent() {
			User admin = UserFactory.admin();
			// Stub: SessionAuthFilter placed the user into the request attribute
			when(req.getAttribute("authenticatedUser")).thenReturn(admin);
			assertThat(controller.publicGetUser(req)).isSameAs(admin);
		}

		/**
		 * Verifies null is returned when the attribute is absent.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when authenticatedUser attribute is absent")
		void returnsNullWhenNoAttribute() {
			// Stub: no user attribute → unauthenticated request
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			assertThat(controller.publicGetUser(req)).isNull();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAuth()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for the {@code requireAuth} guard — pass/401.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("requireAuth()")
	class RequireAuth {

		/**
		 * Verifies the guard passes (returns null) for an authenticated user.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null (pass) when user is authenticated")
		void passesForAuthenticatedUser() {
			// Stub: authenticated user in attribute → guard should pass (return null)
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			assertThat(controller.publicRequireAuth(req)).isNull();
		}

		/**
		 * Verifies the guard returns 401 for an unauthenticated request.
		 *
		 * <p>
		 * The body must be {@code ok=false} with a login-related error message.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 401 when user is null (unauthenticated)")
		void returns401ForUnauthenticated() {
			// Stub: no user → unauthenticated → must return 401
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.publicRequireAuth(req);
			assertThat(result).isNotNull();
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(result.getBody()).containsEntry("ok", false);
			// Error message must indicate login is required
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("login");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAdmin()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for the {@code requireAdmin} guard — role and CSRF enforcement.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("requireAdmin()")
	class RequireAdmin {

		/**
		 * Verifies an admin GET passes with no CSRF header required.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null (pass) for admin GET request with no CSRF needed")
		void passesForAdminGetRequest() {
			// Stub: admin user on a GET request — no CSRF header needed for safe methods
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicRequireAdmin(req)).isNull();
		}

		/**
		 * Verifies an admin POST passes when the CSRF header is present.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null (pass) for admin POST with X-Requested-With header")
		void passesForAdminPostWithCsrf() {
			// Stub: admin user on a POST with the required CSRF header
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicRequireAdmin(req)).isNull();
		}

		/**
		 * Verifies an unauthenticated request returns 401 (not 403).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 401 when user is not authenticated")
		void returns401WhenNotAuthenticated() {
			// Stub: no user → unauthenticated, not just non-admin
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		/**
		 * Verifies an authenticated non-admin returns 403.
		 *
		 * <p>
		 * The error message must mention "admin" so the client understands the
		 * access level required.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 403 when user is authenticated but not admin")
		void returns403WhenNotAdmin() {
			// Stub: regular user (role = "user") — authenticated but lacks admin role
			User regularUser = UserFactory.regularUser(); // role = "user"
			when(req.getAttribute("authenticatedUser")).thenReturn(regularUser);
			ResponseEntity<Map<String, Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(result.getBody()).containsEntry("ok", false);
			// Error message must mention "admin" so the client understands the access level
			// required
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("admin");
		}

		/**
		 * Verifies an admin POST without the CSRF header is blocked with 403.
		 *
		 * <p>
		 * SECURITY: the error must reference CSRF so the client knows which header
		 * is missing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 403 when admin POST is missing X-Requested-With (CSRF blocked)")
		void returns403WhenAdminPostMissingCsrf() {
			// Stub: admin user on a POST without the CSRF header → blocked
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.publicRequireAdmin(req);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			// SECURITY: error must reference CSRF so the client knows what header is
			// missing
			assertThat(result.getBody().get("error").toString()).containsIgnoringCase("CSRF");
		}

		/**
		 * Verifies the CSRF check also applies to DELETE.
		 *
		 * <p>
		 * DELETE is a mutating method, so a missing CSRF header must yield 403 the
		 * same as POST.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("CSRF check applies to DELETE method too")
		void returns403ForDeleteWithoutCsrf() {
			// Stub: admin user on a DELETE without the CSRF header
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.admin());
			when(req.getMethod()).thenReturn("DELETE");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			ResponseEntity<Map<String, Object>> result = controller.publicRequireAdmin(req);
			// DELETE is a mutating method — CSRF check applies the same as POST
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// requireAuthWithCsrf()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for the {@code requireAuthWithCsrf} guard — auth + CSRF.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("requireAuthWithCsrf()")
	class RequireAuthWithCsrf {

		/**
		 * Verifies any authenticated GET passes (safe method).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("passes for any authenticated GET — no CSRF needed")
		void passesForAuthenticatedGet() {
			// GET is a safe method — CSRF protection not required
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicRequireAuthWithCsrf(req)).isNull();
		}

		/**
		 * Verifies an authenticated POST with the CSRF header passes.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("passes for authenticated POST with X-Requested-With")
		void passesForAuthenticatedPostWithCsrf() {
			// Stub: regular user on POST with the required CSRF header
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicRequireAuthWithCsrf(req)).isNull();
		}

		/**
		 * Verifies an unauthenticated request returns 401 before the CSRF check.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 401 for unauthenticated request")
		void returns401ForUnauthenticated() {
			// Stub: no user → authentication check fails before CSRF check
			when(req.getAttribute("authenticatedUser")).thenReturn(null);
			assertThat(controller.publicRequireAuthWithCsrf(req).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		/**
		 * Verifies an authenticated POST without the CSRF header returns 403.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns 403 for authenticated POST without X-Requested-With (CSRF)")
		void returns403ForMissingCsrf() {
			// Stub: authenticated user but missing CSRF header on POST → blocked
			when(req.getAttribute("authenticatedUser")).thenReturn(UserFactory.regularUser());
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			assertThat(controller.publicRequireAuthWithCsrf(req).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// isCsrfOk()
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code isCsrfOk()} — the CSRF predicate.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("isCsrfOk()")
	class IsCsrfOk {

		/**
		 * Verifies GET always passes the CSRF check.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("GET always passes CSRF check")
		void getAlwaysPasses() {
			// Safe methods (GET, HEAD) do not require CSRF headers
			when(req.getMethod()).thenReturn("GET");
			assertThat(controller.publicIsCsrfOk(req)).isTrue();
		}

		/**
		 * Verifies a POST with the X-Requested-With header passes.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("POST with X-Requested-With passes CSRF check")
		void postWithHeaderPasses() {
			// XMLHttpRequest header proves the request was made by JS, not a cross-origin
			// form
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
			assertThat(controller.publicIsCsrfOk(req)).isTrue();
		}

		/**
		 * Verifies a POST without the header fails the CSRF check.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("POST without X-Requested-With fails CSRF check")
		void postWithoutHeaderFails() {
			// SECURITY: POST without CSRF header must be rejected
			when(req.getMethod()).thenReturn("POST");
			when(req.getHeader("x-requested-with")).thenReturn(null);
			assertThat(controller.publicIsCsrfOk(req)).isFalse();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// sanitizeText() — XSS protection
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code sanitizeText()} — HTML escaping and XSS defence.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("sanitizeText() — XSS protection")
	class SanitizeText {

		/**
		 * Verifies null input passes through as null.
		 *
		 * <p>
		 * Callers may use null to mean "not provided", so it must not throw or be
		 * coerced.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null input returns null without NPE")
		void nullInputReturnsNull() {
			// Null must pass through as null — callers may use null to indicate "not
			// provided"
			assertThat(TestableController.publicSanitize(null)).isNull();
		}

		/**
		 * Verifies blank/whitespace input normalises to an empty string.
		 *
		 * @param input a blank or whitespace-only value from the value source
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@ParameterizedTest(name = "blank input ''{0}'' returns empty string")
		@ValueSource(strings = { "", "   ", "\t", "\n" })
		void blankInputReturnsEmpty(String input) {
			// Blank/whitespace-only input should be normalised to empty string
			assertThat(TestableController.publicSanitize(input)).isEmpty();
		}

		/**
		 * Verifies angle brackets are HTML-escaped.
		 *
		 * <p>
		 * No literal {@code <}/{@code >} may remain; they become {@code &lt;}/
		 * {@code &gt;}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("< and > are HTML-escaped to &lt; and &gt;")
		void escapesAngleBrackets() {
			String result = TestableController.publicSanitize("<script>alert(1)</script>");
			// Angle brackets must be escaped — no literal < or > remain
			assertThat(result).doesNotContain("<").doesNotContain(">");
			assertThat(result).contains("&lt;", "&gt;");
		}

		/**
		 * Verifies ampersands are HTML-escaped.
		 *
		 * <p>
		 * A raw {@code &} must become {@code &amp;} to prevent entity injection.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("& is HTML-escaped to &amp;")
		void escapesAmpersand() {
			String result = TestableController.publicSanitize("AT&T");
			// Raw & must be escaped to prevent HTML entity injection
			assertThat(result).contains("&amp;");
			assertThat(result).doesNotContain("AT&T"); // raw & gone
		}

		/**
		 * Verifies double quotes are HTML-escaped.
		 *
		 * <p>
		 * Unescaped quotes could break out of an HTML attribute context, so they
		 * become {@code &quot;}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("double quote is HTML-escaped to &quot;")
		void escapesDoubleQuote() {
			String result = TestableController.publicSanitize("say \"hello\"");
			// Unescaped double quotes in HTML attributes could break out of attribute
			// context
			assertThat(result).contains("&quot;");
		}

		/**
		 * Verifies the {@code onerror} image vector is neutralised.
		 *
		 * <p>
		 * The angle brackets must be escaped so no executable {@code <img>} tag
		 * remains.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies the {@code javascript:} anchor vector is neutralised.
		 *
		 * <p>
		 * The opening anchor tag must be escaped so no active {@code <a>} remains.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies clean text passes through unchanged.
		 *
		 * <p>
		 * The sanitiser must not corrupt input that needs no escaping.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("clean text passes through unchanged (no unnecessary escaping)")
		void cleanTextPassesThrough() {
			String input = "Hello, World! This is a clean message.";
			// Sanitizer must not corrupt clean input
			assertThat(TestableController.publicSanitize(input)).isEqualTo(input);
		}

		/**
		 * Verifies leading/trailing whitespace is stripped.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("leading/trailing whitespace is stripped")
		void stripsLeadingTrailingWhitespace() {
			assertThat(TestableController.publicSanitize("  hello  ")).isEqualTo("hello");
		}
	}
}
