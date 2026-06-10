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
 * <h3>Why this class exists</h3>
 * <p>
 * Guards input validation and BUG-030: a not-configured/unreachable TTS provider
 * ({@link ProviderUnavailableException}) must map to 503 (an environmental
 * condition), while genuine faults stay 500. Also pins the blank-text 400 and
 * the unauthenticated 401.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link VoiceService} is mocked and stubbed per-test to return audio
 * bytes or throw the relevant exception type.</li>
 * <li>The request is armed as an authenticated user with the CSRF header and
 * POST method so tests pass {@code guardAuthWithCsrf}.</li>
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
@DisplayName("VoiceController.speak() — validation + provider 503 (BUG-030)")
class VoiceControllerTest {

	/** Mocked TTS/STT service; stubbed per-test to return audio or throw. */
	@Mock VoiceService voiceService;
	/** Mocked request carrying the authenticated user + CSRF header. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private VoiceController controller;

	/**
	 * Constructs the controller and arms the request as an authenticated user
	 * carrying the CSRF header with a POST method, so each test reaches the speak
	 * logic past {@code guardAuthWithCsrf} unless it overrides the user.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
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

	/**
	 * Blank synthesis text is caller error and must be rejected with a 400 before
	 * the TTS service is ever invoked.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("blank text → 400")
	void blankTextRejected() {
		ResponseEntity<?> r = controller.speak(Map.of("text", ""), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * A missing or unreachable TTS provider is an environmental condition, not a
	 * server bug: when the service throws {@link ProviderUnavailableException} the
	 * controller must translate it to 503 (Service Unavailable), not 500. This is
	 * the core BUG-030 guard.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("provider not configured → 503 (not 500)")
	void providerUnavailableMaps503() throws Exception {
		when(voiceService.speak(anyString(), anyString()))
				.thenThrow(new ProviderUnavailableException("OpenAI API key not configured for TTS"));
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	/**
	 * Any other (genuine) service failure must remain a 500 so real faults are not
	 * masked behind the environmental 503.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("other failure → 500")
	void otherErrorMaps500() throws Exception {
		when(voiceService.speak(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * On success the controller returns 200 with the raw MP3 byte body so the
	 * browser can play it directly.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("success → 200 with audio bytes")
	void successReturnsAudio() throws Exception {
		when(voiceService.speak(anyString(), anyString())).thenReturn(new byte[] { 1, 2, 3 });
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(r.getBody()).isInstanceOf(byte[].class);
	}

	/**
	 * With no authenticated user the {@code guardAuthWithCsrf} guard short-circuits
	 * to 401 before any service call.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unauthenticated → 401")
	void unauthenticatedRejected() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		ResponseEntity<?> r = controller.speak(Map.of("text", "hello"), req);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
