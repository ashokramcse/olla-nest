package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoiceService}.
 *
 * <p>Covers construction and the error path for {@code transcribe()} and
 * {@code speak()} when the OpenAI API key is blank. Real HTTP calls are never
 * issued.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VoiceService — unit tests")
class VoiceServiceTest {

    @Mock DatabaseService dbService;
    @Mock ObjectMapper mapper;

    @InjectMocks VoiceService service;

    @BeforeEach
    void stubDefaults() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{}");
    }

    // ── construction ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("service instantiates without throwing")
        void constructionSucceeds() {
            assertThat(service).isNotNull();
        }
    }

    // ── transcribe() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("transcribe() — OpenAI provider with missing API key")
    class Transcribe {

        @Test
        @DisplayName("throws RuntimeException (not NPE) when openai provider configured but key blank")
        void throwsMeaningfulExceptionWhenKeyBlank() {
            // Stub: STT provider is "openai" but the API key is blank (misconfiguration)
            when(dbService.getSetting(eq("sttProvider"), anyString())).thenReturn("openai");
            when(dbService.getSetting(eq("openaiApiKey"), anyString())).thenReturn("");

            // SECURITY: blank key must throw an informative error rather than sending
            // a request with an empty Authorization header (which would 401 but also
            // expose that the endpoint is being called without credentials)
            assertThatThrownBy(() -> service.transcribe(new byte[]{1, 2, 3}, "audio.webm"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("OpenAI API key not configured");
        }
    }

    // ── speak() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("speak() — missing API key")
    class Speak {

        @Test
        @DisplayName("throws RuntimeException (not NPE) when openaiApiKey is blank")
        void throwsMeaningfulExceptionWhenKeyBlank() {
            // Stub: TTS always uses OpenAI; key is blank
            when(dbService.getSetting(eq("openaiApiKey"), anyString())).thenReturn("");

            // SECURITY: must fail loudly — a blank key is a configuration error,
            // not a valid state that should produce silent fallback behaviour
            assertThatThrownBy(() -> service.speak("Hello world", "alloy"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("OpenAI API key not configured");
        }
    }
}
