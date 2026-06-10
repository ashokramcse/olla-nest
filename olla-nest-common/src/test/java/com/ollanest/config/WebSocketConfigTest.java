package com.ollanest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ollanest.service.TerminalService;

/**
 * Smoke tests for {@link WebSocketConfig}.
 *
 * <p>
 * Verifies that the WebSocket configuration class can be instantiated with
 * mocked dependencies without requiring a full Spring context.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebSocketConfig — unit tests")
class WebSocketConfigTest {

	@Mock
	TerminalService terminalService;
	@Mock
	WebSocketAuthInterceptor authInterceptor;

	/**
	 * The WebSocket configuration must construct cleanly with its mocked terminal
	 * handler and auth interceptor — a smoke test that the WebSocket wiring has no
	 * constructor-time defects.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("WebSocketConfig instantiates without throwing")
	void constructionSucceeds() {
		// No exception thrown = WebSocket config wires with mocked handler +
		// interceptor
		assertThatCode(() -> new WebSocketConfig(terminalService, authInterceptor)).doesNotThrowAnyException();
	}

	/**
	 * The constructor must return a usable, non-null config instance.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("WebSocketConfig instance is non-null")
	void instanceIsNotNull() {
		// Constructor must return a usable config instance
		assertThat(new WebSocketConfig(terminalService, authInterceptor)).isNotNull();
	}
}
