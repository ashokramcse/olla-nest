package com.ollanest.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

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

import com.ollanest.controller.admin.AdminModelsController;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.ModelService;
import com.ollanest.service.OllamaService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Regression tests for BUG-029: model ids containing a slash (e.g. the Ollama
 * namespaced id {@code ollama:user/model:tag}) could not be governed because a
 * raw {@code /} mis-routes (404) and an encoded {@code %2F} is rejected by
 * Tomcat (400). The body-based route {@code PATCH /api/admin/models/governance}
 * carries the id in the JSON body so every model is governable.
 *
 * @author Ashok Ram
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminModelsController — body-based governance (BUG-029)")
class AdminModelsGovernanceTest {

	@Mock
	JdbcTemplate db;
	@Mock
	ModelService modelService;
	@Mock
	OllamaService ollamaService;
	@Mock
	ChatService chatService;
	@Mock
	DataSource dataSource;
	@Mock
	HttpServletRequest req;

	private AdminModelsController controller;

	/**
	 * Builds the controller (its constructor needs a non-null {@code DataSource}
	 * for the transaction template) and arms the request as an authenticated admin
	 * with the CSRF header so each test starts past the auth guard unless it
	 * overrides.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		when(db.getDataSource()).thenReturn(dataSource);
		controller = new AdminModelsController(db, modelService, ollamaService, chatService);
		User admin = new User();
		admin.id = "u-admin-001";
		admin.role = "admin";
		admin.email = "admin@example.com";
		lenient().when(req.getAttribute("authenticatedUser")).thenReturn(admin);
		// guardAdmin requires the CSRF header on non-GET methods.
		lenient().when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
	}

	/**
	 * The body-based governance route requires an {@code id} field; omitting it is
	 * caller error and must return a 400 without touching any model row.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("missing id in body → 400")
	void missingIdRejected() {
		ResponseEntity<Map<String, Object>> r = controller.updateGovernanceByBody(Map.of("governanceTier", "approved"),
				req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false).doesNotContainKey("model");
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("id");
	}

	/**
	 * Core BUG-029 guard: a slash-containing model id (e.g. the Ollama namespaced
	 * {@code ollama:user/model:tag}) reaches the {@code WHERE id = ?} lookup fully
	 * intact, proving the body route does not truncate or reject it the way a
	 * path-variable would. With no matching model the controller returns 404.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("slash-containing id is passed through intact to the model lookup (404 when absent)")
	void slashIdReachesLookup() {
		String slashId = "ollama:dimavz/whisper-tiny:latest";
		// The body route must hand the full slash id to the WHERE id = ? lookup —
		// proving the id is not truncated/rejected by path routing.
		when(db.queryForList(eq("SELECT id FROM models WHERE id = ?"), eq(slashId))).thenReturn(List.of());
		ResponseEntity<Map<String, Object>> r = controller
				.updateGovernanceByBody(Map.of("id", slashId, "governanceTier", "approved"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("not found");
	}

	/**
	 * A state-changing PATCH without the {@code x-requested-with} CSRF header must be
	 * forbidden with a 403, even for an authenticated admin.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("PATCH without CSRF header is forbidden (403)")
	void missingCsrfHeaderForbidden() {
		when(req.getHeader("x-requested-with")).thenReturn(null);
		ResponseEntity<Map<String, Object>> r = controller
				.updateGovernanceByBody(Map.of("id", "ollama:gemma3:1b", "governanceTier", "approved"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * With no authenticated user the {@code requireAdmin} guard rejects the request
	 * (401/403) and no governance change is applied.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unauthenticated request is rejected (no governance applied)")
	void unauthenticatedRejected() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		ResponseEntity<Map<String, Object>> r = controller
				.updateGovernanceByBody(Map.of("id", "ollama:gemma3:1b", "governanceTier", "approved"), req);
		assertThat(r.getStatusCode().value()).isIn(401, 403);
	}
}
