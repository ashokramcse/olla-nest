package com.ollanest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link VisualReportService}.
 *
 * <p>This service is pure computation — it is constructed directly with
 * {@code new VisualReportService()} (no Mockito mocks needed). Tests cover:
 * {@code generate()} — valid HTML output; DOCTYPE present; query string in output;
 * null markdown produces valid HTML (no NPE); empty sources list; duration included.
 *
 * <p>No Spring context, no real DB, no network calls.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VisualReportService — unit tests")
class VisualReportServiceTest {

    /** Direct construction — no dependencies. */
    private final VisualReportService svc = new VisualReportService();

    // ── generate() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generate()")
    class Generate {

        @Test
        @DisplayName("result starts with <!DOCTYPE html>")
        void startsWithDoctype() {
            // Full report with one source — well-formed HTML5 document required
            String html = svc.generate("AI trends", "## Intro\nContent here.",
                    List.of(Map.of("title", "Source 1", "url", "https://example.com")), 5000);
            // DOCTYPE required for browsers to render in standards mode
            assertThat(html.trim()).startsWith("<!DOCTYPE html>");
        }

        @Test
        @DisplayName("query string appears in output (in title or hero)")
        void queryAppearsInOutput() {
            // Query should appear in the report title or hero section for context
            String html = svc.generate("quantum computing research", "# Body",
                    List.of(), 3000);
            assertThat(html).contains("quantum computing research");
        }

        @Test
        @DisplayName("null markdown produces valid HTML without NPE")
        void nullMarkdownNoException() {
            // Null markdown: sometimes the LLM produces no body text — must not crash
            assertThatCode(() -> svc.generate("test query", null, List.of(), 1000))
                    .doesNotThrowAnyException();
            // Even without markdown the HTML skeleton must be complete
            String html = svc.generate("test query", null, List.of(), 1000);
            assertThat(html.trim()).startsWith("<!DOCTYPE html>");
        }

        @Test
        @DisplayName("empty sources list produces valid HTML")
        void emptySourcesValidHtml() {
            // No citations — report is still generated (pure LLM synthesis path)
            String html = svc.generate("test", "# Title", List.of(), 0);
            assertThat(html).contains("<!DOCTYPE html>");
        }

        @Test
        @DisplayName("null sources list produces valid HTML (no NPE)")
        void nullSourcesNoException() {
            // Null sources list must be treated as empty — no NPE
            assertThatCode(() -> svc.generate("query", "content", null, 2000))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("duration is included in output (seconds format)")
        void durationInOutput() {
            // durationMs 7500 → durationS = 7500/1000.0 = 7.5 — displayed in the report footer
            String html = svc.generate("test", "body", List.of(), 7500);
            // durationS = 7500/1000.0 = 7.5
            assertThat(html).contains("7.5");
        }

        @Test
        @DisplayName("source count appears in output when sources provided")
        void sourceCountInOutput() {
            // Two sources → source count "2" must appear somewhere in the rendered page
            var sources = List.of(
                    Map.<String, Object>of("title", "A", "url", "https://a.com"),
                    Map.<String, Object>of("title", "B", "url", "https://b.com")
            );
            String html = svc.generate("query", "body", sources, 1000);
            assertThat(html).contains("2");
        }

        @Test
        @DisplayName("markdown headings appear in rendered body")
        void markdownHeadingsRendered() {
            // Markdown ## headings should be converted to HTML <h2> (or similar) in the body
            String html = svc.generate("topic", "## Key Findings\nSome text.", List.of(), 1000);
            assertThat(html).contains("Key Findings");
        }

        @Test
        @DisplayName("HTML special chars in query are escaped")
        void htmlCharsInQueryEscaped() {
            // SECURITY: query with XSS payload must be escaped so script tag is not injected
            String html = svc.generate("<script>alert(1)</script>", "body", List.of(), 0);
            // Raw <script> tag must never appear in the HTML output
            assertThat(html).doesNotContain("<script>");
        }
    }
}
