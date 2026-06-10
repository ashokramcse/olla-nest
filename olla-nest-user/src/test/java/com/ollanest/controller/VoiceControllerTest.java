package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.ollanest.model.User;
import com.ollanest.service.VoiceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link VoiceController#speak}.
 *
 * <p>
 * Focus: input validation and BUG-030 — a not-configured/unreachable TTS
 * provider ({@link ProviderUnavailableException}) maps to 503 (environmental),
 * not 500 (server fault).
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VoiceController.speak() — validation + provider 503 (BUG-030)")
class VoiceControllerTest {

	@Mock VoiceService voiceService;
	@Mock HttpServletRequest req;

	private VoiceController controller;

	@BeforeEach
	void setUp() {
		controller = new VoiceController(voiceService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
		when(req.getHeader("x-requested-with")).thenReturn("XMLHttpRequest");
		when(req.getMethod()).thenReturn("POST");
	}

	@Test
	@DisplayName("blank text → 400")
	void blankTextRejected() {
		ResponseEntity<?> r = controller.speak(Map.of("text", ""), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("provider not configured → 503 (not 500)")
	void providerUnavailableMaps503() throws Exception {
		when(voiceService.speak(anyString(), anyString()))
				.thenThrow(new ProviderUnavailableException("OpenAI API key not configured for TTS"));
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	@DisplayName("other failure → 500")
	void otherErrorMaps500() throws Exception {
		when(voiceService.speak(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("success → 200 with audio bytes")
	void successReturnsAudio() throws Exception {
		when(voiceService.speak(anyString(), anyString())).thenReturn(new byte[] { 1, 2, 3 });
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).isInstanceOf(byte[].class);
	}

	@Test
	@DisplayName("unauthenticated → 401")
	void unauthenticatedRejected() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
