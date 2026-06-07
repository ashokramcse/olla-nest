package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link ProviderService}.
 *
 * <p>Covers resolveProvider(), ProviderResult construction, and mirrorApiModelToModels().
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProviderService — unit tests")
class ProviderServiceTest {

    @Mock JdbcTemplate db;
    @Mock CryptoService cryptoService;
    @Mock OllamaService ollamaService;
    @Mock ObjectMapper mapper;

    @InjectMocks ProviderService providerService;

    private RouterService.RouteResult ollamaRoute() {
        ModelRecord m = new ModelRecord();
        m.id = "llama3.2";
        m.provider = "ollama";
        m.modelRef = "llama3.2";
        m.name = "LLaMA 3.2";
        return new RouterService.RouteResult(m, "auto");
    }

    private RouterService.RouteResult apiRoute(String providerId) {
        ModelRecord m = new ModelRecord();
        m.id = providerId + ":gpt-4";
        m.provider = providerId;
        m.modelRef = "gpt-4";
        m.name = "GPT-4";
        return new RouterService.RouteResult(m, "forced");
    }

    // ── resolveProvider() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveProvider()")
    class ResolveProvider {

        @Test
        @DisplayName("returns synthetic ollama map for ollama provider")
        void ollamaProvider() {
            // Stub: Ollama URL and encryption for the synthetic provider map
            when(ollamaService.ollamaUrl()).thenReturn("http://localhost:11434");
            when(cryptoService.encryptKey("")).thenReturn("enc-empty");
            Map<String, Object> result = providerService.resolveProvider(ollamaRoute());
            // Synthetic Ollama provider must always report type="ollama" and name="Ollama"
            assertThat(result.get("type")).isEqualTo("ollama");
            assertThat(result.get("name")).isEqualTo("Ollama");
        }

        @Test
        @DisplayName("returns DB row for API provider when found and enabled")
        void apiProvider() {
            // Stub: DB returns a configured, enabled provider row
            Map<String, Object> row = Map.of("id", "provider-1", "type", "openai",
                    "name", "OpenAI", "base_url", "https://api.openai.com/v1",
                    "api_key_enc", "enc-key", "enabled", 1);
            when(db.queryForList(anyString(), eq("provider-1"))).thenReturn(List.of(row));
            Map<String, Object> result = providerService.resolveProvider(apiRoute("provider-1"));
            // Returned map must contain the provider name from the DB row
            assertThat(result.get("name")).isEqualTo("OpenAI");
        }

        @Test
        @DisplayName("throws when provider not found")
        void throwsWhenNotFound() {
            // Stub: no provider with this ID in DB — must throw with a clear message
            when(db.queryForList(anyString(), eq("unknown-provider"))).thenReturn(List.of());
            assertThatThrownBy(() -> providerService.resolveProvider(apiRoute("unknown-provider")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not configured");
        }

        @Test
        @DisplayName("throws when provider is disabled")
        void throwsWhenDisabled() {
            // Stub: provider exists but is disabled by admin
            Map<String, Object> row = Map.of("id", "provider-1", "type", "openai",
                    "name", "OpenAI", "enabled", 0);
            when(db.queryForList(anyString(), eq("provider-1"))).thenReturn(List.of(row));
            // Disabled providers must not be used — throw to prevent silent fallback
            assertThatThrownBy(() -> providerService.resolveProvider(apiRoute("provider-1")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("disabled");
        }
    }

    // ── ProviderResult construction ───────────────────────────────────────────

    @Nested
    @DisplayName("ProviderResult")
    class ProviderResultTests {

        @Test
        @DisplayName("content, tokensUsed, providerName fields are accessible")
        void fieldsAccessible() {
            // Verify all three result fields are correctly stored and accessible to callers
            ProviderService.ProviderResult result = new ProviderService.ProviderResult("Hello", 42, "Ollama");
            assertThat(result.content).isEqualTo("Hello");
            assertThat(result.tokensUsed).isEqualTo(42);
            assertThat(result.providerName).isEqualTo("Ollama");
        }

        @Test
        @DisplayName("toolCalls is null by default")
        void toolCallsNullByDefault() {
            // toolCalls is only populated for tool-use responses — must be null for standard completions
            ProviderService.ProviderResult result = new ProviderService.ProviderResult("content", 0, "test");
            assertThat(result.toolCalls).isNull();
        }
    }

    // ── mirrorApiModelToModels() ──────────────────────────────────────────────

    @Nested
    @DisplayName("mirrorApiModelToModels()")
    class MirrorApiModelToModels {

        @Test
        @DisplayName("INSERT/UPSERT is called when model is approved")
        void upsertWhenApproved() {
            // Stub: provider and approved API model — must be mirrored into the models table
            Map<String, Object> provider = Map.of("id", "provider-1", "name", "OpenAI");
            Map<String, Object> apiModel = Map.of("model_id", "gpt-4", "is_approved", true,
                    "display_name", "GPT-4");
            providerService.mirrorApiModelToModels(provider, apiModel);
            // INSERT must be called to add the approved model to the local models registry
            verify(db).update(contains("INSERT INTO models"), (Object[]) any());
        }

        @Test
        @DisplayName("DELETE is called when model is not approved")
        void deleteWhenNotApproved() {
            // Stub: model exists but is_approved=false — it must be removed from the models table
            Map<String, Object> provider = Map.of("id", "provider-1", "name", "OpenAI");
            Map<String, Object> apiModel = Map.of("model_id", "gpt-old", "is_approved", false);
            providerService.mirrorApiModelToModels(provider, apiModel);
            // DELETE must be called to remove the revoked model so it is no longer routable
            verify(db).update(contains("DELETE FROM models"), anyString());
        }
    }
}
