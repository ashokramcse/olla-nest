package com.ollanest.config;

import com.ollanest.service.TerminalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke tests for {@link WebSocketConfig}.
 *
 * <p>Verifies that the WebSocket configuration class can be instantiated
 * with mocked dependencies without requiring a full Spring context.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebSocketConfig — unit tests")
class WebSocketConfigTest {

    @Mock TerminalService terminalService;
    @Mock WebSocketAuthInterceptor authInterceptor;

    @Test
    @DisplayName("WebSocketConfig instantiates without throwing")
    void constructionSucceeds() {
        assertThatCode(() -> new WebSocketConfig(terminalService, authInterceptor))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WebSocketConfig instance is non-null")
    void instanceIsNotNull() {
        assertThat(new WebSocketConfig(terminalService, authInterceptor)).isNotNull();
    }
}
