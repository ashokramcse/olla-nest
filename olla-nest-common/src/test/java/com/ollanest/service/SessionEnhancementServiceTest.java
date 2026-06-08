package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link SessionEnhancementService}.
 *
 * <p>Covers: {@code forkSession()} — NoSuchElementException when not found, new session has
 * "Fork: " title prefix, messages are copied, result map contains forked_session_id;
 * {@code truncateHistory()} — NoSuchElementException when session not found, NoSuchElementException
 * when pivot message not found, DELETE called with session_id and pivotDate;
 * {@code analyzeTopicsAsync()} — does not throw.
 *
 * <p>All DB interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SessionEnhancementService — unit tests")
class SessionEnhancementServiceTest {

    private static final String OWNER = UserFactory.USER_ID;
    private static final String SESSION_ID = "sess-test-001";

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock DatabaseService databaseService;

    @InjectMocks SessionEnhancementService svc;

    // ── forkSession() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("forkSession()")
    class ForkSession {

        @Test
        @DisplayName("throws NoSuchElementException when session not found for owner")
        void throwsWhenSessionNotFound() {
            // Stub: session not found for this owner (may not exist or belong to different user)
            when(db.queryForList(contains("FROM chat_sessions WHERE id"), eq(SESSION_ID), eq(OWNER)))
                    .thenReturn(List.of());
            // Exception message must include session ID for debuggability
            assertThatThrownBy(() -> svc.forkSession(SESSION_ID, OWNER, 5))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining(SESSION_ID);
        }

        @Test
        @DisplayName("creates new session with 'Fork: ' title prefix")
        void newSessionHasForkPrefix() {
            // Stub: original session exists
            stubSessionFound("My Chat");
            when(db.queryForList(contains("FROM chat_messages WHERE session_id"), eq(SESSION_ID), anyInt()))
                    .thenReturn(List.of());
            svc.forkSession(SESSION_ID, OWNER, 0);
            // Forked session title must start with "Fork: " so the user can identify it
            verify(db).update(contains("INSERT INTO chat_sessions"), any(), any(),
                    argThat(arg -> arg.toString().startsWith("Fork: ")), any(), any());
        }

        @Test
        @DisplayName("returns map with forked_session_id")
        void returnsMapWithForkedSessionId() {
            // Stub: original session exists with no messages
            stubSessionFound("My Chat");
            when(db.queryForList(contains("FROM chat_messages WHERE session_id"), eq(SESSION_ID), anyInt()))
                    .thenReturn(List.of());
            var result = svc.forkSession(SESSION_ID, OWNER, 0);
            // Caller needs forked_session_id to redirect the user to the new session
            assertThat(result).containsKey("forked_session_id");
            assertThat(result.get("forked_session_id").toString()).startsWith("sess-fork-");
        }

        @Test
        @DisplayName("copies messages via INSERT into chat_messages")
        void copiesMessages() {
            // Stub: original session with one message
            stubSessionFound("Test Session");
            var msg = Map.<String, Object>of("id", "msg-1", "session_id", SESSION_ID,
                    "user_id", OWNER, "role", "user", "content", "Hello", "created_at", "2026-01-01T00:00:00Z");
            when(db.queryForList(contains("FROM chat_messages WHERE session_id"), eq(SESSION_ID), anyInt()))
                    .thenReturn(List.of(msg));
            svc.forkSession(SESSION_ID, OWNER, 0);
            // Each message must be copied to the new session via INSERT
            verify(db, atLeastOnce()).update(contains("INSERT INTO chat_messages"), any(Object[].class));
        }

        @Test
        @DisplayName("message_count in result equals number of messages copied")
        void messageCountMatchesCopied() {
            // Stub: original session with two messages
            stubSessionFound("Session A");
            var msgs = List.of(
                    Map.<String, Object>of("id", "m1", "role", "user", "content", "Hi",
                            "user_id", OWNER, "created_at", "2026-01-01T00:00:00Z"),
                    Map.<String, Object>of("id", "m2", "role", "assistant", "content", "Hello",
                            "user_id", OWNER, "created_at", "2026-01-01T00:00:01Z")
            );
            when(db.queryForList(contains("FROM chat_messages WHERE session_id"), eq(SESSION_ID), anyInt()))
                    .thenReturn(msgs);
            var result = svc.forkSession(SESSION_ID, OWNER, 1);
            // message_count in result must match the actual number of messages copied
            assertThat(result.get("message_count")).isEqualTo(2);
        }

        private void stubSessionFound(String title) {
            when(db.queryForList(contains("FROM chat_sessions WHERE id"), eq(SESSION_ID), eq(OWNER)))
                    .thenReturn(List.of(Map.of("id", SESSION_ID, "user_id", OWNER, "title", title)));
        }
    }

    // ── truncateHistory() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("truncateHistory()")
    class TruncateHistory {

        @Test
        @DisplayName("throws NoSuchElementException when session not found")
        void throwsWhenSessionNotFound() {
            // Stub: session does not exist for this owner
            when(db.queryForList(contains("FROM chat_sessions WHERE id"), eq(SESSION_ID), eq(OWNER)))
                    .thenReturn(List.of());
            // Must throw with session ID so the caller knows which session was missing
            assertThatThrownBy(() -> svc.truncateHistory(SESSION_ID, OWNER, "msg-pivot"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining(SESSION_ID);
        }

        @Test
        @DisplayName("throws NoSuchElementException when pivot message not found")
        void throwsWhenPivotMessageNotFound() {
            // Stub: session found but the pivot message does not exist
            when(db.queryForList(contains("FROM chat_sessions WHERE id"), eq(SESSION_ID), eq(OWNER)))
                    .thenReturn(List.of(Map.of("id", SESSION_ID)));
            when(db.queryForList(contains("FROM chat_messages WHERE id"), eq("msg-missing"), eq(SESSION_ID)))
                    .thenReturn(List.of());
            // Pivot message not found — must throw with the message ID for diagnostics
            assertThatThrownBy(() -> svc.truncateHistory(SESSION_ID, OWNER, "msg-missing"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("msg-missing");
        }

        @Test
        @DisplayName("calls DELETE WHERE session_id=? AND created_at >= pivotDate")
        void callsDeleteWithPivotDate() {
            // Stub: session found and pivot message exists with a known timestamp
            when(db.queryForList(contains("FROM chat_sessions WHERE id"), eq(SESSION_ID), eq(OWNER)))
                    .thenReturn(List.of(Map.of("id", SESSION_ID)));
            when(db.queryForList(contains("FROM chat_messages WHERE id"), eq("msg-pivot"), eq(SESSION_ID)))
                    .thenReturn(List.of(Map.of("created_at", "2026-01-15T10:00:00Z")));
            svc.truncateHistory(SESSION_ID, OWNER, "msg-pivot");
            // DELETE must include the pivot timestamp so only messages at or after it are removed
            verify(db).update(contains("DELETE FROM chat_messages WHERE session_id"), eq(SESSION_ID),
                    eq("2026-01-15T10:00:00Z"));
        }
    }

    // ── analyzeTopicsAsync() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("analyzeTopicsAsync()")
    class AnalyzeTopicsAsync {

        @Test
        @DisplayName("does not throw — fire and forget virtual thread")
        void doesNotThrow() {
            // analyzeTopicsAsync is fire-and-forget on a virtual thread — must never throw to caller
            assertThatCode(() -> svc.analyzeTopicsAsync(SESSION_ID, OWNER))
                    .doesNotThrowAnyException();
        }
    }
}
