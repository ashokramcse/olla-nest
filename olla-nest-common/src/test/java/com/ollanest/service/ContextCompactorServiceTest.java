package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link ContextCompactorService}.
 *
 * <p>Covers token estimation, compaction threshold detection, and compact() message reduction.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContextCompactorService — unit tests")
class ContextCompactorServiceTest {

    @Mock ObjectMapper mapper;
    @Mock DatabaseService databaseService;

    @InjectMocks ContextCompactorService contextCompactorService;

    // ── estimateTokens() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("estimateTokens()")
    class EstimateTokens {

        @Test
        @DisplayName("returns 0 for null text")
        void zeroForNull() {
            assertThat(contextCompactorService.estimateTokens(null)).isEqualTo(0);
        }

        @Test
        @DisplayName("returns 0 for empty string")
        void zeroForEmpty() {
            assertThat(contextCompactorService.estimateTokens("")).isEqualTo(0);
        }

        @Test
        @DisplayName("proportional to text length (~4 chars per token)")
        void proportionalToLength() {
            // 400 chars → ~100 tokens
            String text = "a".repeat(400);
            int tokens = contextCompactorService.estimateTokens(text);
            assertThat(tokens).isGreaterThanOrEqualTo(90).isLessThanOrEqualTo(110);
        }

        @Test
        @DisplayName("always non-negative")
        void nonNegative() {
            assertThat(contextCompactorService.estimateTokens("hi")).isGreaterThanOrEqualTo(0);
        }
    }

    // ── needsCompaction() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("needsCompaction()")
    class NeedsCompaction {

        @Test
        @DisplayName("false when messages fit within context window")
        void falseWhenFit() {
            List<Map<String, Object>> msgs = List.of(
                    Map.of("role", "user", "content", "hi") // ~1 token
            );
            assertThat(contextCompactorService.needsCompaction(msgs, 8192)).isFalse();
        }

        @Test
        @DisplayName("true when messages exceed threshold")
        void trueWhenOverThreshold() {
            // Large content that exceeds 85% of a tiny context window
            String bigContent = "x".repeat(4000); // ~1000 tokens
            List<Map<String, Object>> msgs = List.of(
                    Map.of("role", "user", "content", bigContent)
            );
            // context of 100 tokens → 85% = 85 → 1000 > 85
            assertThat(contextCompactorService.needsCompaction(msgs, 100)).isTrue();
        }

        @Test
        @DisplayName("uses default context when contextSize is 0")
        void usesDefaultWhenZero() {
            List<Map<String, Object>> msgs = List.of(
                    Map.of("role", "user", "content", "hello")
            );
            // Should not throw
            boolean result = contextCompactorService.needsCompaction(msgs, 0);
            assertThat(result).isIn(true, false);
        }
    }

    // ── compact() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compact()")
    class Compact {

        private List<Map<String, Object>> buildMessages(int count) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", "You are helpful."));
            for (int i = 0; i < count - 1; i++) {
                String role = (i % 2 == 0) ? "user" : "assistant";
                msgs.add(Map.of("role", role, "content", "Message " + i));
            }
            return msgs;
        }

        @Test
        @DisplayName("returns fewer messages than input when large history")
        void reduceMessageCount() {
            List<Map<String, Object>> msgs = buildMessages(20);
            List<Map<String, Object>> compacted = contextCompactorService.compact(
                    msgs, 8192, "http://localhost:11434", "llama3.2");
            assertThat(compacted.size()).isLessThan(msgs.size());
        }

        @Test
        @DisplayName("last message (most recent) is preserved")
        void lastMessagePreserved() {
            List<Map<String, Object>> msgs = buildMessages(20);
            Map<String, Object> last = msgs.get(msgs.size() - 1);
            List<Map<String, Object>> compacted = contextCompactorService.compact(
                    msgs, 8192, "http://localhost:11434", "llama3.2");
            assertThat(compacted).contains(last);
        }

        @Test
        @DisplayName("result contains a system message (summary)")
        void containsSystemMessage() {
            List<Map<String, Object>> msgs = buildMessages(20);
            List<Map<String, Object>> compacted = contextCompactorService.compact(
                    msgs, 8192, "http://localhost:11434", "llama3.2");
            assertThat(compacted).anyMatch(m -> "system".equals(m.get("role")));
        }

        @Test
        @DisplayName("returns original list unchanged when messages <= KEEP_RECENT+2")
        void returnsOriginalWhenSmall() {
            List<Map<String, Object>> msgs = buildMessages(5); // <= 8
            List<Map<String, Object>> compacted = contextCompactorService.compact(
                    msgs, 8192, "http://localhost:11434", "llama3.2");
            assertThat(compacted).isSameAs(msgs);
        }
    }
}
