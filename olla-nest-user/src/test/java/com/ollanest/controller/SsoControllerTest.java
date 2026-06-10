package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.CryptoService;
import com.ollanest.service.SsoService;
import com.ollanest.service.UserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link SsoController#adminCreateProvider}.
 *
 * <p>
 * Focus: BUG-035 — creating an SSO provider with a missing {@code type}/
 * {@code name} must be a 400 (the columns are NOT-NULL) rather than a 500;
 * a valid create encrypts the client secret and persists once.
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SsoController.adminCreateProvider() — validation (BUG-035)")
class SsoControllerTest {

	/** SSO service collaborator (not exercised by the create path under test). */
	@Mock SsoService ssoService;
	/** Auth service collaborator (not exercised by the create path under test). */
	@Mock AuthService authService;
	/** Crypto service used to encrypt the client secret at rest. */
	@Mock CryptoService cryptoService;
	/** User service collaborator (not exercised by the create path under test). */
	@Mock UserService userService;
	/** Mocked JDBC template; verified to ensure rows are/aren't persisted. */
	@Mock JdbcTemplate db;
	/** Mocked request carrying the authenticated user + CSRF header. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked collaborators. */
	private SsoController controller;

	/**
	 * Builds the controller and arms the request as an authenticated admin with the
	 * CSRF header so each test starts past the auth guard unless it overrides the
	 * user.
	 */
	@BeforeEach
	void setUp() {
		controller = new SsoController(ssoService, authService, cryptoService, userService, db);
		User admin = new User();
		admin.id = "u-admin-001";
		admin.role = "admin";
		admin.email = "admin@example.com";
		when(req.getAttribute("authenticatedUser")).thenReturn(admin);
		when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
	}

	/**
	 * An empty body omits the NOT-NULL {@code type}/{@code name}; the controller must
	 * return 400 with a clear message and must not attempt an INSERT (BUG-035).
	 */
	@Test
	@DisplayName("empty body → 400, nothing persisted")
	void emptyBodyRejected() {
		ResponseEntity<Map<String, Object>> r = controller.adminCreateProvider(Map.of(), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("type and name are required");
		verify(db, never()).update(contains("INSERT INTO sso_providers"), any(Object[].class));
	}

	/** A type without a name is still incomplete → 400, no INSERT. */
	@Test
	@DisplayName("missing name → 400")
	void missingNameRejected() {
		ResponseEntity<Map<String, Object>> r = controller.adminCreateProvider(Map.of("type", "oidc"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO sso_providers"), any(Object[].class));
	}

	/**
	 * With both required fields the provider persists once, and the plaintext client
	 * secret is routed through {@code encryptKey} — never stored raw.
	 */
	@Test
	@DisplayName("valid type+name → 200, secret encrypted, persists once")
	void validCreateEncryptsAndPersists() {
		when(cryptoService.encryptKey("supersecret")).thenReturn("ENC(supersecret)");
		ResponseEntity<Map<String, Object>> r = controller
				.adminCreateProvider(Map.of("type", "oidc", "name", "Okta", "clientSecret", "supersecret"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).containsEntry("ok", true);
		// The plaintext secret must be encrypted, never persisted raw.
		verify(cryptoService).encryptKey("supersecret");
		verify(db).update(contains("INSERT INTO sso_providers"), any(Object[].class));
	}

	/** A non-admin caller is blocked by the auth guard before any persistence. */
	@Test
	@DisplayName("non-admin is forbidden (no persist)")
	void nonAdminForbidden() {
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
		ResponseEntity<Map<String, Object>> r = controller
				.adminCreateProvider(Map.of("type", "oidc", "name", "X"), req);
		assertThat(r.getStatusCode().value()).isIn(401, 403);
		verify(db, never()).update(contains("INSERT INTO sso_providers"), any(Object[].class));
	}
}
