package com.ollanest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCD-level unit tests for {@link PromptTemplateService}.
 *
 * <p>Covers buildSystemPrompt() variable substitution and getModeInstructions().
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PromptTemplateService — unit tests")
class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    // ── buildSystemPrompt() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSystemPrompt()")
    class BuildSystemPrompt {

        @Test
        @DisplayName("returns non-null non-blank prompt")
        void nonBlankPrompt() {
            // A blank system prompt would leave the LLM without any behavioral guidance
            String prompt = service.buildSystemPrompt("ask", "llama3.2", "auto", null, null, null);
            assertThat(prompt).isNotBlank();
        }

        @Test
        @DisplayName("contains model name when provided")
        void containsModelName() {
            // Model name must appear in the prompt so the LLM knows its own identity
            String prompt = service.buildSystemPrompt("ask", "llama3.2", "auto", null, null, null);
            assertThat(prompt).contains("llama3.2");
        }

        @Test
        @DisplayName("contains mode in output")
        void containsMode() {
            // Mode ("build", "ask", etc.) shapes the tone and instructions — must appear in prompt
            String prompt = service.buildSystemPrompt("build", "model", "reason", null, null, null);
            assertThat(prompt).contains("build");
        }

        @Test
        @DisplayName("unknown mode falls back to 'ask' instruction")
        void unknownModeFallsBack() {
            // Unknown mode must not crash — falls back to the default "ask" instruction set
            String promptAsk = service.buildSystemPrompt("ask", "m", "r", null, null, null);
            String promptUnknown = service.buildSystemPrompt("xyz-unknown", "m", "r", null, null, null);
            // Both should contain the ask instruction set (give a clear answer)
            assertThat(promptUnknown).contains("clear");
        }

        @Test
        @DisplayName("null mode defaults to ask behavior")
        void nullModeDefaultsToAsk() {
            // Null mode must not throw — treated as if "ask" was specified
            assertThatCode(() -> service.buildSystemPrompt(null, null, null, null, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("projectKnowledge is included when non-blank")
        void projectKnowledgeIncluded() {
            // Project knowledge section must be injected so the LLM has workspace-specific context
            String prompt = service.buildSystemPrompt("ask", "m", "r", null, "Project rules: no hardcoding", null);
            assertThat(prompt).contains("Project Knowledge:");
            assertThat(prompt).contains("no hardcoding");
        }

        @Test
        @DisplayName("ragContext is included when non-blank")
        void ragContextIncluded() {
            // RAG context must be included in the prompt so retrieved documents are visible to the LLM
            String prompt = service.buildSystemPrompt("ask", "m", "r", null, null, "RAG: relevant chunk here");
            assertThat(prompt).contains("RAG: relevant chunk here");
        }

        @Test
        @DisplayName("workspaceInfo is included when non-blank")
        void workspaceInfoIncluded() {
            // Workspace metadata must be surfaced to help the LLM personalise responses
            String prompt = service.buildSystemPrompt("ask", "m", "r", "Workspace: ACME Corp", null, null);
            assertThat(prompt).contains("ACME Corp");
        }
    }

    // ── getModeInstructions() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getModeInstructions()")
    class GetModeInstructions {

        @Test
        @DisplayName("returns non-empty map")
        void nonEmpty() {
            // An empty map means all modes fall back to default — that would break mode routing
            assertThat(service.getModeInstructions()).isNotEmpty();
        }

        @Test
        @DisplayName("contains 'ask' mode")
        void containsAskMode() {
            // "ask" is the default mode — it must always have instructions
            assertThat(service.getModeInstructions()).containsKey("ask");
        }

        @Test
        @DisplayName("contains 'build', 'review', 'fix', 'debug', 'test', 'docs', 'plan', 'learn' modes")
        void containsAllModes() {
            // All UI-visible modes must have corresponding instruction entries
            Map<String, String> modes = service.getModeInstructions();
            assertThat(modes).containsKeys("ask", "build", "review", "fix", "debug", "test", "docs", "plan", "learn");
        }

        @Test
        @DisplayName("map is unmodifiable")
        void isUnmodifiable() {
            Map<String, String> modes = service.getModeInstructions();
            // Map must be unmodifiable — callers must not be able to mutate the shared instruction set
            assertThatThrownBy(() -> modes.put("new_mode", "instruction"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
