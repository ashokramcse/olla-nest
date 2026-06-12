package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * OCD-level unit tests for pure string-processing methods in
 * {@link WorkspaceService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The workspace pipeline turns raw model output into displayable content and
 * extractable build artifacts. The transformation rules are subtle — stripping
 * model reasoning ({@code <think>} blocks), recognising fenced and bare code
 * blocks, and normalising permission modes — and a regression here silently
 * leaks reasoning tokens to users or drops artifacts. These tests lock the
 * pure-computation paths so they can be refactored safely.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Exercises {@code cleanModelOutput()}, {@code extractArtifacts()} and
 * {@code normalizePermissionMode()} — none of which touch the filesystem or a
 * Spring context.</li>
 * <li>{@link Strictness#LENIENT} avoids unnecessary-stubbing noise even though
 * the mocked collaborators are largely unused by these pure paths.</li>
 * <li>Parameterized tests enumerate the allowlisted permission modes and a
 * representative set of rejected values (including wrong case).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — initial creation covering output cleaning, artifact
 * extraction and permission-mode normalisation.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkspaceService — unit tests")
class WorkspaceServiceTest {

	/** Mocked JDBC template; unused by the pure paths but required for injection. */
	@Mock
	JdbcTemplate db;
	/** Mocked database service collaborator. */
	@Mock
	DatabaseService databaseService;

	/** Service under test with mocks injected. */
	@InjectMocks
	WorkspaceService svc;

	// ── cleanModelOutput() ────────────────────────────────────────────────────

	/**
	 * Tests for {@code cleanModelOutput()} — reasoning-token stripping.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("cleanModelOutput()")
	class CleanModelOutput {

		/**
		 * Verifies null input yields an empty string rather than throwing.
		 *
		 * <p>
		 * Streaming can produce null content nodes; the cleaner must null-guard
		 * and return {@code ""}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null input returns empty string")
		void nullReturnsEmpty() {
			// Null guard — streaming can produce null content nodes
			assertThat(WorkspaceService.cleanModelOutput(null)).isEmpty();
		}

		/**
		 * Verifies content without think tags passes through unchanged.
		 *
		 * <p>
		 * The standard response path has no {@code <think>} blocks, so the input
		 * must be returned byte-for-byte.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("content without think tags is returned unchanged")
		void noThinkTagsUnchanged() {
			// Standard response path: no think blocks → no transformation needed
			String content = "This is a plain response with no thinking.";
			assertThat(WorkspaceService.cleanModelOutput(content)).isEqualTo(content);
		}

		/**
		 * Verifies a closed {@code <think>…</think>} block is removed entirely.
		 *
		 * <p>
		 * Models such as DeepSeek wrap internal reasoning in think tags; the UI
		 * must never show those tokens, so only the trailing response survives.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("closed <think>…</think> block is stripped")
		void closedThinkTagStripped() {
			// DeepSeek and similar models wrap internal reasoning in <think>…</think>
			// The UI must never show raw reasoning tokens to the user
			String content = "<think>Internal reasoning goes here.</think>Actual response.";
			assertThat(WorkspaceService.cleanModelOutput(content)).isEqualTo("Actual response.");
		}

		/**
		 * Verifies multiple think blocks are all stripped while interleaved text
		 * survives.
		 *
		 * <p>
		 * With two think blocks around middle/end text, none of the reasoning
		 * content may leak and both non-think segments must be preserved.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies an unclosed think tag still yields the post-fence code.
		 *
		 * <p>
		 * When a model opens {@code <think>} but emits a code fence before
		 * closing, the fence acts as a structural marker and the code after it
		 * must be preserved in the output.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("unclosed <think> tag with code fence marker — content after fence preserved")
		void unclosedThinkTagWithFenceMarker() {
			// Edge case: model opened <think> but included code inside it before closing
			// The code fence acts as a structural marker — content after ``` is kept
			String content = "<think>reasoning...\n```html\n<h1>Hello</h1>\n```";
			String result = WorkspaceService.cleanModelOutput(content);
			assertThat(result).contains("<h1>Hello</h1>");
		}

		/**
		 * Verifies an empty string input returns an empty string.
		 *
		 * <p>
		 * The empty string is a valid no-op and must not throw.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("empty string input returns empty string")
		void emptyStringReturnsEmpty() {
			// Empty string is a valid no-op case
			assertThat(WorkspaceService.cleanModelOutput("")).isEmpty();
		}
	}

	// ── extractArtifacts() ────────────────────────────────────────────────────

	/**
	 * Tests for {@code extractArtifacts()} — code-block detection and naming.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("extractArtifacts()")
	class ExtractArtifacts {

		/**
		 * Verifies a fenced HTML block becomes one artifact with {@code ext=html}.
		 *
		 * <p>
		 * The most common artifact type; the extension drives file naming and
		 * syntax highlighting, and the body must contain the original markup.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a fenced JS block becomes one artifact with {@code ext=js}.
		 *
		 * <p>
		 * The {@code js} extension triggers JavaScript highlighting in the
		 * artifact panel.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("fenced JS block is extracted with ext='js'")
		void fencedJsExtracted() {
			// JS extension triggers JavaScript syntax highlighting in the artifact panel
			String content = "```js\nconsole.log('hello');\n```";
			var artifacts = svc.extractArtifacts(content, "write a script");
			assertThat(artifacts).hasSize(1);
			assertThat(artifacts.get(0).ext).isEqualTo("js");
		}

		/**
		 * Verifies the {@code ```lang:filename} syntax sets the artifact name.
		 *
		 * <p>
		 * An explicit filename in the fence header overrides the auto-generated
		 * name.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("fenced block with :filename uses parsed filename")
		void fencedBlockWithFilename() {
			// `` ```html:index.html `` syntax: explicit filename overrides auto-generated
			// name
			String content = "```html:index.html\n<html></html>\n```";
			var artifacts = svc.extractArtifacts(content, "msg");
			assertThat(artifacts).hasSize(1);
			assertThat(artifacts.get(0).name).isEqualTo("index.html");
		}

		/**
		 * Verifies a bare {@code <html>…</html>} block is detected without fences.
		 *
		 * <p>
		 * Some models emit raw HTML; the fallback detector must still produce one
		 * {@code html} artifact.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("bare <html>…</html> block detected when no fences present")
		void bareHtmlDetected() {
			// Fallback: some models emit raw HTML without fences
			String content = "Here is the result:\n<html><body><p>Hello</p></body></html>";
			var artifacts = svc.extractArtifacts(content, "create html");
			assertThat(artifacts).hasSize(1);
			assertThat(artifacts.get(0).ext).isEqualTo("html");
		}

		/**
		 * Verifies plain text yields an empty (non-null) artifact list.
		 *
		 * <p>
		 * Pure prose has no code, so the result must be an empty list rather than
		 * null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("plain text with no code returns empty list")
		void plainTextNoArtifacts() {
			// Pure text responses have no artifacts — list must be empty, not null
			String content = "Here is a simple text response with no code at all.";
			var artifacts = svc.extractArtifacts(content, "explain something");
			assertThat(artifacts).isEmpty();
		}

		/**
		 * Verifies two fenced blocks produce two distinct artifacts.
		 *
		 * <p>
		 * An HTML and a CSS block in one response must each become a separate
		 * workspace artifact.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("multiple fenced blocks produce multiple artifacts")
		void multipleFencedBlocks() {
			// HTML + CSS in one response → two separate artifacts for the workspace
			String content = "```html\n<h1>Page</h1>\n```\n```css\nbody{margin:0}\n```";
			var artifacts = svc.extractArtifacts(content, "build page");
			assertThat(artifacts).hasSize(2);
		}

		/**
		 * Verifies the artifact name is derived from the user message when no
		 * explicit filename is given.
		 *
		 * <p>
		 * The generated name must reflect the request (here, "dashboard") so
		 * artifacts are discoverable.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

	/**
	 * Tests for {@code normalizePermissionMode()} — allowlist enforcement.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("normalizePermissionMode()")
	class NormalizePermissionMode {

		/**
		 * Verifies allowlisted modes are returned unchanged.
		 *
		 * <p>
		 * Each of {@code default}/{@code review}/{@code full} is a recognised mode
		 * and must pass through verbatim for storage.
		 *
		 * @param mode an allowlisted permission mode supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@ParameterizedTest
		@ValueSource(strings = { "default", "review", "full" })
		@DisplayName("known modes are returned as-is")
		void knownModesPassThrough(String mode) {
			// Allowlisted modes: "default", "review", "full" — stored directly in DB
			assertThat(svc.normalizePermissionMode(mode)).isEqualTo(mode);
		}

		/**
		 * Verifies unknown, null, empty or wrong-case modes fall back to
		 * {@code "default"}.
		 *
		 * <p>
		 * Any value outside the allowlist (including {@code "FULL"}) must collapse
		 * to the safest mode, {@code "default"}.
		 *
		 * @param mode a rejected/blank permission mode supplied by the sources
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "admin", "read-only", "superuser", "FULL" })
		@DisplayName("unknown modes fall back to 'default'")
		void unknownModesFallBackToDefault(String mode) {
			// All unrecognised values (including wrong case) fall back to "default"
			// (safest)
			assertThat(svc.normalizePermissionMode(mode)).isEqualTo("default");
		}
	}
}
