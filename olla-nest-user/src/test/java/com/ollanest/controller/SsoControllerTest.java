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
 * <h3>Why this class exists</h3>
 * <p>
 * Guards BUG-035: creating an SSO provider whose body omits the NOT-NULL
 * {@code type}/{@code name} columns must be a 400 rather than a 500, and a valid
 * create must route the client secret through {@link CryptoService} so it is
 * never persisted in plaintext.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Collaborators are Mockito mocks; the {@link JdbcTemplate} and
 * {@link CryptoService} are verified to confirm persistence and encryption.</li>
 * <li>The request is armed as an authenticated admin with the CSRF header.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created for the BUG-035 fix and the controller test-coverage
 * pass.</li>
 * </ul>
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
	 * Constructs the controller with the mocked collaborators and arms the request
	 * as an authenticated admin carrying the CSRF header, so each test reaches the
	 * provider-create logic past the {@code requireAdmin} guard unless it overrides
	 * the authenticated user.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
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
	 * An empty body omits the NOT-NULL {@code type}/{@code name} columns. The
	 * controller must return a 400 with a clear "type and name are required" message
	 * and issue no INSERT, proving the BUG-035 guard runs before persistence.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
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

	/**
	 * A body with a {@code type} but no {@code name} is still incomplete and must be
	 * rejected with a 400 and no INSERT.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("missing name → 400")
	void missingNameRejected() {
		ResponseEntity<Map<String, Object>> r = controller.adminCreateProvider(Map.of("type", "oidc"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO sso_providers"), any(Object[].class));
	}

	/**
	 * With both required fields present the provider is persisted exactly once and
	 * the plaintext client secret is routed through {@code encryptKey} — proving it
	 * is encrypted at rest and never stored raw.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
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

	/**
	 * A non-admin caller must be blocked by the {@code requireAdmin} guard before
	 * any persistence, returning 401 or 403 and issuing no INSERT.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
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
