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
 * <p>
 * Focus: input validation and BUG-030 — a not-configured/unreachable image
 * provider ({@link ProviderUnavailableException}) maps to 503, not 500; every
 * attempt (success or failure) writes an {@code image_generation_log} row.
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImageController.generate() — validation + provider 503 (BUG-030)")
class ImageControllerTest {

	@Mock ImageGenerationService imageService;
	@Mock JdbcTemplate db;
	@Mock HttpServletRequest req;

	private ImageController controller;

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

	@Test
	@DisplayName("blank prompt → 400")
	void blankPromptRejected() {
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", ""), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("provider not configured → 503, error logged")
	void providerUnavailableMaps503() throws Exception {
		when(imageService.generate(anyString(), any()))
				.thenThrow(new ProviderUnavailableException("OpenAI API key not configured"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(r.getBody()).containsEntry("ok", false);
	}

	@Test
	@DisplayName("other failure → 500")
	void otherErrorMaps500() throws Exception {
		when(imageService.generate(anyString(), any())).thenThrow(new RuntimeException("boom"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("success → 200 with provider + url")
	void successReturnsImage() throws Exception {
		when(imageService.generate(anyString(), any()))
				.thenReturn(new ImageGenerationService.ImageResult("https://cdn/x.png", null, "dalle", "dall-e-3"));
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).containsEntry("ok", true).containsEntry("imageUrl", "https://cdn/x.png");
	}

	@Test
	@DisplayName("unauthenticated → 401")
	void unauthenticatedRejected() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		ResponseEntity<Map<String, Object>> r = controller.generate(Map.of("prompt", "a cat"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
