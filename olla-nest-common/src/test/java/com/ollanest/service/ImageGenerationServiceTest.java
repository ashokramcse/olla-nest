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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ImageGenerationService}.
 *
 * <p>Covers the {@link ImageGenerationService.ImageResult} record, and the error
 * path in {@code generate()} when the OpenAI API key is absent (provider defaults
 * to DALL-E). Real HTTP calls are never issued.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImageGenerationService — unit tests")
class ImageGenerationServiceTest {

    @Mock DatabaseService dbService;
    @Mock ObjectMapper mapper;

    @InjectMocks ImageGenerationService service;

    @BeforeEach
    void stubDefaults() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{}");
    }

    // ── ImageResult record ────────────────────────────────────────────────────

    @Nested
    @DisplayName("ImageResult record")
    class ImageResultRecord {

        @Test
        @DisplayName("url accessor returns the URL passed to constructor")
        void urlAccessor() {
            var r = new ImageGenerationService.ImageResult("https://cdn.openai.com/img.png", null, "dalle", "dall-e-3");
            assertThat(r.url()).isEqualTo("https://cdn.openai.com/img.png");
        }

        @Test
        @DisplayName("base64 accessor returns the base64 string")
        void base64Accessor() {
            var r = new ImageGenerationService.ImageResult(null, "aGVsbG8=", "stable-diffusion", "sd");
            assertThat(r.base64()).isEqualTo("aGVsbG8=");
        }

        @Test
        @DisplayName("provider and model accessors return expected values")
        void providerAndModelAccessors() {
            var r = new ImageGenerationService.ImageResult("url", null, "dalle", "dall-e-3");
            assertThat(r.provider()).isEqualTo("dalle");
            assertThat(r.model()).isEqualTo("dall-e-3");
        }
    }

    // ── generate() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generate() — DALL-E with blank API key")
    class GenerateDalleBlankKey {

        @Test
        @DisplayName("throws RuntimeException (not NPE) when openaiApiKey is blank")
        void throwsWhenApiKeyBlank() {
            when(dbService.getSetting(eq("imageProvider"), anyString())).thenReturn("dalle");
            when(dbService.getSetting(eq("openaiApiKey"), anyString())).thenReturn("");
            when(dbService.getSetting(eq("imageModel"), anyString())).thenReturn("dall-e-3");
            when(dbService.getSetting(eq("imageSize"), anyString())).thenReturn("1024x1024");

            assertThatThrownBy(() -> service.generate("a red cat", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("OpenAI API key not configured");
        }

        @Test
        @DisplayName("requestedProvider 'dalle' overrides setting and also throws when key blank")
        void requestedProviderOverride() {
            when(dbService.getSetting(eq("openaiApiKey"), anyString())).thenReturn("");
            when(dbService.getSetting(eq("imageModel"), anyString())).thenReturn("dall-e-3");
            when(dbService.getSetting(eq("imageSize"), anyString())).thenReturn("1024x1024");

            assertThatThrownBy(() -> service.generate("a blue sky", "dalle"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("OpenAI API key not configured");
        }
    }
}
