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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link AgentLoopService}.
 *
 * <p>Covers isRunning() and cancel() — testable without SSE or LLM connections.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentLoopService — unit tests")
class AgentLoopServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock ObjectMapper mapper;
    @Mock JdbcTemplate db;
    @Mock WebSearchService webSearchService;
    @Mock MemoryService memoryService;
    @Mock NotesService notesService;
    @Mock CalendarService calendarService;
    @Mock TaskSchedulerService taskService;

    @InjectMocks AgentLoopService agentLoopService;

    // ── isRunning() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRunning()")
    class IsRunning {

        @Test
        @DisplayName("returns false for unknown sessionId")
        void falseForUnknown() {
            assertThat(agentLoopService.isRunning("unknown-session-xyz")).isFalse();
        }
    }

    // ── cancel() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("no exception for unknown sessionId")
        void noExceptionForUnknown() {
            assertThatCode(() -> agentLoopService.cancel("unknown-session-abc"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("after cancel, isRunning returns false")
        void isRunningFalseAfterCancel() {
            // Put a session in the active runs by calling cancel first (no exception)
            agentLoopService.cancel("session-123");
            assertThat(agentLoopService.isRunning("session-123")).isFalse();
        }
    }
}
