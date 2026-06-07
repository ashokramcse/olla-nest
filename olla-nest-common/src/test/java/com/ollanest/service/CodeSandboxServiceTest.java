package com.ollanest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link CodeSandboxService} — pure/static parts only.
 *
 * <p>{@code run()} is not tested as it spawns real processes.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodeSandboxService — unit tests")
class CodeSandboxServiceTest {

    // ── supportedLanguages() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("supportedLanguages()")
    class SupportedLanguages {

        @Test
        @DisplayName("returns non-empty set")
        void nonEmpty() {
            // At least one language must be supported for the sandbox to be useful
            Set<String> langs = CodeSandboxService.supportedLanguages();
            assertThat(langs).isNotEmpty();
        }

        @Test
        @DisplayName("includes 'python'")
        void includesPython() {
            // Python is a core supported language for data analysis and scripting
            assertThat(CodeSandboxService.supportedLanguages()).contains("python");
        }

        @Test
        @DisplayName("includes 'javascript'")
        void includesJavascript() {
            // JavaScript is a core supported language for web-side code execution
            assertThat(CodeSandboxService.supportedLanguages()).contains("javascript");
        }

        @Test
        @DisplayName("includes 'bash'")
        void includesBash() {
            // Bash support enables shell script execution in the sandbox
            assertThat(CodeSandboxService.supportedLanguages()).contains("bash");
        }

        @Test
        @DisplayName("includes 'java'")
        void includesJava() {
            // Java support enables JVM code execution in the sandbox
            assertThat(CodeSandboxService.supportedLanguages()).contains("java");
        }
    }

    // ── RunResult record ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("RunResult record")
    class RunResultTests {

        @Test
        @DisplayName("can be constructed with all fields accessible")
        void canBeConstructed() {
            // Verify all record accessors work correctly
            CodeSandboxService.RunResult result = new CodeSandboxService.RunResult(
                    true, "Hello World", 0, 123L, "run", null);
            assertThat(result.ok()).isTrue();
            assertThat(result.output()).isEqualTo("Hello World");
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(result.elapsedMs()).isEqualTo(123L);
            assertThat(result.phase()).isEqualTo("run");
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("error factory creates failed result with phase 'error'")
        void errorFactory() {
            // error() factory is used when execution cannot start (e.g. Docker unavailable)
            CodeSandboxService.RunResult result = CodeSandboxService.RunResult.error("Something went wrong");
            assertThat(result.ok()).isFalse();
            assertThat(result.phase()).isEqualTo("error");
            assertThat(result.error()).isEqualTo("Something went wrong");
            assertThat(result.exitCode()).isEqualTo(-1);
        }

        @Test
        @DisplayName("timeout factory creates failed result with phase 'timeout'")
        void timeoutFactory() {
            // timeout() factory is used when the time limit is exceeded
            CodeSandboxService.RunResult result = CodeSandboxService.RunResult.timeout(10);
            assertThat(result.ok()).isFalse();
            assertThat(result.phase()).isEqualTo("timeout");
            // Timeout message must include the limit so the user knows how long they had
            assertThat(result.error()).contains("10");
        }

        @Test
        @DisplayName("unsupported factory creates failed result with phase 'unsupported'")
        void unsupportedFactory() {
            // unsupported() factory is used for unrecognised language identifiers
            CodeSandboxService.RunResult result = CodeSandboxService.RunResult.unsupported("cobol");
            assertThat(result.ok()).isFalse();
            assertThat(result.phase()).isEqualTo("unsupported");
            // Error message must name the unsupported language for actionable feedback
            assertThat(result.error()).contains("cobol");
        }

        @Test
        @DisplayName("of() factory sets ok based on exitCode 0")
        void ofFactoryExitZero() {
            // Exit code 0 = success by Unix convention
            CodeSandboxService.RunResult result = CodeSandboxService.RunResult.of("output", 0, 50L, "run");
            assertThat(result.ok()).isTrue();
        }

        @Test
        @DisplayName("of() factory sets ok=false for non-zero exit code")
        void ofFactoryExitNonZero() {
            // Non-zero exit code = process reported failure
            CodeSandboxService.RunResult result = CodeSandboxService.RunResult.of("error output", 1, 50L, "run");
            assertThat(result.ok()).isFalse();
        }
    }
}
