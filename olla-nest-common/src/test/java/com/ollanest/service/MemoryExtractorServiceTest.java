package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OCD-level unit tests for {@link MemoryExtractorService}.
 *
 * <p>Covers: {@code maybeExtract()} — skipped when user message count is not a multiple of 10;
 * skipped when count is 0; no exception when count is exactly 10 (async HTTP path swallows errors);
 * skipped when count is 20 or other multiples (verify consistently triggered).
 *
 * <p>The async HTTP extraction path is fire-and-forget — we only assert on the
 * synchronous interval check logic.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryExtractorService — unit tests")
class MemoryExtractorServiceTest {

    private static final String OWNER = UserFactory.USER_ID;
    private static final String SESSION = "sess-extract-001";

    @Mock MemoryService memoryService;
    @Mock DatabaseService databaseService;
    @Mock ObjectMapper mapper;

    @InjectMocks MemoryExtractorService svc;

    /** Build a list of N user messages + M assistant messages. */
    private static List<Map<String, Object>> messages(int userCount, int assistantCount) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            msgs.add(Map.of("role", "user", "content", "User message " + i));
        }
        for (int i = 0; i < assistantCount; i++) {
            msgs.add(Map.of("role", "assistant", "content", "Assistant reply " + i));
        }
        return msgs;
    }

    // ── maybeExtract() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("maybeExtract()")
    class MaybeExtract {

        @Test
        @DisplayName("does nothing when user message count is 0")
        void skipsWhenCountZero() throws Exception {
            // 0 user messages → no extraction trigger (count % 10 != 0 for count=0 guard)
            svc.maybeExtract(OWNER, SESSION, messages(0, 5));
            Thread.sleep(100);
            // memoryService.remember() must never be called synchronously here
            verify(memoryService, never()).remember(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does nothing when user message count is 1 (not multiple of 10)")
        void skipsWhenCountOne() throws Exception {
            // 1 is not a multiple of 10 — extraction skipped
            svc.maybeExtract(OWNER, SESSION, messages(1, 3));
            Thread.sleep(100);
            verify(memoryService, never()).remember(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does nothing when user message count is 9 (not multiple of 10)")
        void skipsWhenCountNine() throws Exception {
            // 9 < 10 — boundary check: extraction fires only at multiples of 10
            svc.maybeExtract(OWNER, SESSION, messages(9, 4));
            Thread.sleep(100);
            verify(memoryService, never()).remember(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does nothing when user message count is 11 (not multiple of 10)")
        void skipsWhenCountEleven() throws Exception {
            // 11 % 10 = 1 ≠ 0 — no extraction between the 10 and 20 triggers
            svc.maybeExtract(OWNER, SESSION, messages(11, 2));
            Thread.sleep(100);
            verify(memoryService, never()).remember(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not throw when count is exactly 10 (triggers async extraction)")
        void noExceptionWhenCountTen() {
            // HTTP call to Ollama will fail (offline in test) but is swallowed — async path
            assertThatCode(() -> svc.maybeExtract(OWNER, SESSION, messages(10, 5)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw when count is exactly 20 (another valid interval)")
        void noExceptionWhenCountTwenty() {
            // 20 % 10 = 0 — another valid extraction trigger; same fire-and-forget semantics
            assertThatCode(() -> svc.maybeExtract(OWNER, SESSION, messages(20, 8)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("returns immediately (non-blocking) for non-trigger count")
        void returnsImmediatelyForNonTrigger() {
            // The synchronous check (count % 10 != 0) must be near-instant — no I/O
            long start = System.currentTimeMillis();
            svc.maybeExtract(OWNER, SESSION, messages(7, 3));
            long elapsed = System.currentTimeMillis() - start;
            // Must return well under any HTTP timeout (500 ms generous upper bound)
            assertThat(elapsed).isLessThan(500);
        }
    }
}
