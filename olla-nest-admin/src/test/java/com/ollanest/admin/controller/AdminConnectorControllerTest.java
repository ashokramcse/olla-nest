package com.ollanest.admin.controller;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.connector.ConnectorRegistry;
import com.ollanest.controller.admin.AdminConnectorController;
import com.ollanest.model.User;
import com.ollanest.service.CryptoService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link AdminConnectorController#create}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Guards BUG-036: an admin connector create whose body omits the NOT-NULL
 * {@code name}/{@code type} columns must fail fast with a 400 instead of letting
 * a null reach the INSERT and surface as a misleading 500
 * SQLITE_CONSTRAINT_NOTNULL. It also pins the auth guard and the
 * exactly-once-persist behaviour for valid input.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Collaborators are Mockito mocks; the {@link JdbcTemplate} is verified to
 * confirm whether (and how often) an INSERT was issued.</li>
 * <li>The request is armed as an authenticated admin with the CSRF header so the
 * tests exercise the create logic past the {@code requireAdmin} guard.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created for the BUG-036 fix and the controller test-coverage
 * pass.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminConnectorController.create() — validation (BUG-036)")
class AdminConnectorControllerTest {

	/** Mocked JDBC template; verified to ensure rows are/aren't persisted. */
	@Mock JdbcTemplate db;
	/** Connector type registry dependency (unused by the create path under test). */
	@Mock ConnectorRegistry registry;
	/** Crypto service used to encrypt connector credentials at rest. */
	@Mock CryptoService cryptoService;
	/** JSON mapper used to serialise the credentials/config bags. */
	@Mock ObjectMapper mapper;
	/** Mocked request carrying the authenticated user + CSRF header. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked collaborators. */
	private AdminConnectorController controller;

	/**
	 * Constructs the controller with the mocked collaborators and arms the request
	 * as an authenticated admin carrying the {@code x-requested-with} CSRF header
	 * (required by {@code requireAdmin} on non-GET requests). This lets each test
	 * reach the create logic past the auth guard unless it deliberately overrides
	 * the authenticated user.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		controller = new AdminConnectorController(db, registry, cryptoService, mapper);
		User admin = new User();
		admin.id = "u-admin-001";
		admin.role = "admin";
		admin.email = "admin@example.com";
		when(req.getAttribute("authenticatedUser")).thenReturn(admin);
		// requireAdmin requires the CSRF header on non-GET requests.
		when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
	}

	/**
	 * An empty request body omits both NOT-NULL columns ({@code name} and
	 * {@code type}). The controller must short-circuit with a 400 carrying a clear
	 * "name and type are required" message and must not attempt any INSERT — proving
	 * the BUG-036 guard runs before persistence.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("empty body → 400, nothing persisted")
	void emptyBodyRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of(), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("name and type are required");
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	/**
	 * A body with a {@code name} but no {@code type} is still incomplete. The
	 * controller must reject it with a 400 and issue no INSERT.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("missing type → 400")
	void missingTypeRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("name", "X"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	/**
	 * A body with a {@code type} but no {@code name} is still incomplete. The
	 * controller must reject it with a 400 and issue no INSERT.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("missing name → 400")
	void missingNameRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("type", "github"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	/**
	 * With both required fields present the create path persists the connector
	 * exactly once and returns 200 with {@code ok=true}. This confirms the happy
	 * path is unaffected by the added validation.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("valid name+type → 200 and persists once")
	void validCreatePersists() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("type", "github", "name", "My Repo"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).containsEntry("ok", true);
		verify(db).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	/**
	 * A non-admin caller (role {@code user}) must be blocked by the
	 * {@code requireAdmin} guard before any persistence occurs, returning 401 or
	 * 403 and issuing no INSERT.
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
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("type", "github", "name", "X"), req);
		assertThat(r.getStatusCode().value()).isIn(401, 403);
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}
}
