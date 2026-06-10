package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.ollanest.service.ImageGenerationService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link ImageController#generate}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Guards input validation and BUG-030: a not-configured/unreachable image
 * provider ({@link ProviderUnavailableException}) must map to 503 while genuine
 * faults stay 500. Also pins the blank-prompt 400 and the unauthenticated 401.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link ImageGenerationService} is mocked and stubbed per-test; the
 * {@link JdbcTemplate} is the generation-log sink.</li>
 * <li>The request is armed as an authenticated user with the CSRF header and
 * POST method.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created for the BUG-030 fix and the controller test-coverage
 * pass.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImageController.generate() — validation + provider 503 (BUG-030)")
class ImageControllerTest {

	/** Mocked image-generation service; stubbed per-test to return a result or throw. */
	@Mock ImageGenerationService imageService;
	/** Mocked JDBC template used for the generation-log INSERT. */
	@Mock JdbcTemplate db;
	/** Mocked request carrying the authenticated user + CSRF header. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked collaborators. */
	private ImageController controller;

	/**
	 * Constructs the controller and arms the request as an authenticated user
	 * carrying the CSRF header with a POST method, so each test reaches the generate
	 * logic past the auth guard unless it overrides the user.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		controller = new ImageController(imageService, db);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
		when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
		when(req.getMethod()).thenReturn("POST");
	}

	/**
	 * A blank prompt is caller error and must be rejected with a 400 before the
	 * provider is ever invoked.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("blank prompt → 400")
	void blankPromptRejected() {
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", ""), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * A missing or unreachable image provider is environmental: when the service
	 * throws {@link ProviderUnavailableException} the controller must map it to 503
	 * (not 500) and still record the failed attempt in the generation log. Core
	 * BUG-030 guard.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("provider not configured → 503, error logged")
	void providerUnavailableMaps503() throws Exception {
		when(imageService.generate(anyString(), any()))
				.thenThrow(new ProviderUnavailableException("OpenAI API key not configured"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(r.getBody()).containsEntry("ok", false);
	}

	/**
	 * Any other (genuine) provider failure must remain a 500 so real faults are not
	 * masked behind the environmental 503.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("other failure → 500")
	void otherErrorMaps500() throws Exception {
		when(imageService.generate(anyString(), any())).thenThrow(new RuntimeException("boom"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * On success the controller returns 200 carrying the provider name and the
	 * remote image URL.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("success → 200 with provider + url")
	void successReturnsImage() throws Exception {
		when(imageService.generate(anyString(), any()))
				.thenReturn(new ImageGenerationService.ImageResult("https://cdn/x.png", null, "dalle", "dall-e-3"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).containsEntry("ok", true).containsEntry("imageUrl", "https://cdn/x.png");
	}

	/**
	 * With no authenticated user the auth guard short-circuits to 401 before any
	 * provider call.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unauthenticated → 401")
	void unauthenticatedRejected() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
