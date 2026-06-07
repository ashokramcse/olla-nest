package com.ollanest.service;

import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for pure string-processing methods in {@link WorkspaceService}.
 *
 * <p>Covers: {@code cleanModelOutput()} — null safe, think-tag stripping, unclosed think-tag
 * with content markers; {@code extractArtifacts()} — fenced code blocks, language detection,
 * bare HTML fallback, no artifacts for plain text; {@code normalizePermissionMode()} — known
 * modes, unknown falls back to "default".
 *
 * <p>No FS access, no Spring context. These are all pure computation paths.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkspaceService — unit tests")
class WorkspaceServiceTest {

    @Mock JdbcTemplate db;
    @Mock DatabaseService databaseService;

    @InjectMocks WorkspaceService svc;

    // ── cleanModelOutput() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanModelOutput()")
    class CleanModelOutput {

        @Test
        @DisplayName("null input returns empty string")
        void nullReturnsEmpty() {
            // Null guard — streaming can produce null content nodes
            assertThat(WorkspaceService.cleanModelOutput(null)).isEmpty();
        }

        @Test
        @DisplayName("content without think tags is returned unchanged")
        void noThinkTagsUnchanged() {
            // Standard response path: no think blocks → no transformation needed
            String content = "This is a plain response with no thinking.";
            assertThat(WorkspaceService.cleanModelOutput(content)).isEqualTo(content);
        }

        @Test
        @DisplayName("closed <think>…</think> block is stripped")
        void closedThinkTagStripped() {
            // DeepSeek and similar models wrap internal reasoning in <think>…</think>
            // The UI must never show raw reasoning tokens to the user
            String content = "<think>Internal reasoning goes here.</think>Actual response.";
            assertThat(WorkspaceService.cleanModelOutput(content)).isEqualTo("Actual response.");
        }

        @Test
        @DisplayName("multiple <think> blocks are all stripped")
        void multipleThinkTagsStripped() {
            // All think blocks removed — interleaved text sections are preserved
            String content = "<think>first</think>Middle<think>second</think>End";
            String result = WorkspaceService.cleanModelOutput(content);
            // Think-block content must not leak through
            assertThat(result).doesNotContain("<think>").doesNotContain("first").doesNotContain("second");
            // Non-think content must be preserved
            assertThat(result).contains("Middle").contains("End");
        }

        @Test
        @DisplayName("unclosed <think> tag with code fence marker — content after fence preserved")
        void unclosedThinkTagWithFenceMarker() {
            // Edge case: model opened <think> but included code inside it before closing
            // The code fence acts as a structural marker — content after ``` is kept
            String content = "<think>reasoning...\n```html\n<h1>Hello</h1>\n```";
            String result = WorkspaceService.cleanModelOutput(content);
            assertThat(result).contains("<h1>Hello</h1>");
        }

        @Test
        @DisplayName("empty string input returns empty string")
        void emptyStringReturnsEmpty() {
            // Empty string is a valid no-op case
            assertThat(WorkspaceService.cleanModelOutput("")).isEmpty();
        }
    }

    // ── extractArtifacts() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractArtifacts()")
    class ExtractArtifacts {

        @Test
        @DisplayName("fenced HTML block is extracted with ext='html'")
        void fencedHtmlExtracted() {
            // Fenced HTML block — most common artifact type for workspace builds
            String content = "```html\n<h1>Hello World</h1>\n```";
            var artifacts = svc.extractArtifacts(content, "create a page");
            assertThat(artifacts).hasSize(1);
            // ext used to set file extension and syntax highlighting in the UI
            assertThat(artifacts.get(0).ext).isEqualTo("html");
            assertThat(artifacts.get(0).content).contains("<h1>Hello World</h1>");
        }

        @Test
        @DisplayName("fenced JS block is extracted with ext='js'")
        void fencedJsExtracted() {
            // JS extension triggers JavaScript syntax highlighting in the artifact panel
            String content = "```js\nconsole.log('hello');\n```";
            var artifacts = svc.extractArtifacts(content, "write a script");
            assertThat(artifacts).hasSize(1);
            assertThat(artifacts.get(0).ext).isEqualTo("js");
        }

        @Test
        @DisplayName("fenced block with :filename uses parsed filename")
        void fencedBlockWithFilename() {
            // `` ```html:index.html `` syntax: explicit filename overrides auto-generated name
            String content = "```html:index.html\n<html></html>\n```";
            var artifacts = svc.extractArtifacts(content, "msg");
            assertThat(artifacts).hasSize(1);
            assertThat(artifacts.get(0).name).isEqualTo("index.html");
        }

        @Test
        @DisplayName("bare <html>…</html> block detected when no fences present")
        void bareHtmlDetected() {
            // Fallback: some models emit raw HTML without fences
            String content = "Here is the result:\n<html><body><p>Hello</p></body></html>";
            var artifacts = svc.extractArtifacts(content, "create html");
            assertThat(artifacts).hasSize(1);
            assertThat(artifacts.get(0).ext).isEqualTo("html");
        }

        @Test
        @DisplayName("plain text with no code returns empty list")
        void plainTextNoArtifacts() {
            // Pure text responses have no artifacts — list must be empty, not null
            String content = "Here is a simple text response with no code at all.";
            var artifacts = svc.extractArtifacts(content, "explain something");
            assertThat(artifacts).isEmpty();
        }

        @Test
        @DisplayName("multiple fenced blocks produce multiple artifacts")
        void multipleFencedBlocks() {
            // HTML + CSS in one response → two separate artifacts for the workspace
            String content = "```html\n<h1>Page</h1>\n```\n```css\nbody{margin:0}\n```";
            var artifacts = svc.extractArtifacts(content, "build page");
            assertThat(artifacts).hasSize(2);
        }

        @Test
        @DisplayName("artifact name derived from message when no explicit filename")
        void artifactNameDerivedFromMessage() {
            // Name auto-generated from the user message content for discoverability
            String content = "```js\nconsole.log('x');\n```";
            var artifacts = svc.extractArtifacts(content, "create a dashboard");
            assertThat(artifacts).hasSize(1);
            assertThat(artifacts.get(0).name).contains("dashboard");
        }
    }

    // ── normalizePermissionMode() ─────────────────────────────────────────────

    @Nested
    @DisplayName("normalizePermissionMode()")
    class NormalizePermissionMode {

        @ParameterizedTest
        @ValueSource(strings = {"default", "review", "full"})
        @DisplayName("known modes are returned as-is")
        void knownModesPassThrough(String mode) {
            // Allowlisted modes: "default", "review", "full" — stored directly in DB
            assertThat(svc.normalizePermissionMode(mode)).isEqualTo(mode);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"admin", "read-only", "superuser", "FULL"})
        @DisplayName("unknown modes fall back to 'default'")
        void unknownModesFallBackToDefault(String mode) {
            // All unrecognised values (including wrong case) fall back to "default" (safest)
            assertThat(svc.normalizePermissionMode(mode)).isEqualTo("default");
        }
    }
}
