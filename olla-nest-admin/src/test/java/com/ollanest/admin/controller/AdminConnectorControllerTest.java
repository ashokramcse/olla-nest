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
 * <p>
 * Focus: BUG-036 — an admin connector create with a missing {@code name}/
 * {@code type} must be a 400 (the columns are NOT-NULL) rather than a 500
 * SQLITE_CONSTRAINT_NOTNULL; a valid create persists exactly once.
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminConnectorController.create() — validation (BUG-036)")
class AdminConnectorControllerTest {

	@Mock JdbcTemplate db;
	@Mock ConnectorRegistry registry;
	@Mock CryptoService cryptoService;
	@Mock ObjectMapper mapper;
	@Mock HttpServletRequest req;

	private AdminConnectorController controller;

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

	@Test
	@DisplayName("empty body → 400, nothing persisted")
	void emptyBodyRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of(), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("name and type are required");
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	@Test
	@DisplayName("missing type → 400")
	void missingTypeRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("name", "X"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	@Test
	@DisplayName("missing name → 400")
	void missingNameRejected() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("type", "github"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(db, never()).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

	@Test
	@DisplayName("valid name+type → 200 and persists once")
	void validCreatePersists() {
		ResponseEntity<Map<String, Object>> r = controller.create(Map.of("type", "github", "name", "My Repo"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).containsEntry("ok", true);
		verify(db).update(contains("INSERT INTO connector_configs"), any(Object[].class));
	}

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
