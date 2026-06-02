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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link BackgroundJobService}.
 *
 * <p>Covers: {@code register()} — ID format, DB INSERT; {@code updateProgress()} — DB UPDATE;
 * {@code complete()} — status update, thread map removal; {@code fail()} — error persistence,
 * thread map cleanup; {@code cancel()} — thread interrupt path, no-thread path; {@code registerThread()} —
 * thread stored; {@code listActive()} — delegates to DB; {@code listByOwner()} — limit defaulting;
 * {@code getById()} — null when not found.
 *
 * <p>All DB interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackgroundJobService — unit tests")
class BackgroundJobServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks BackgroundJobService svc;

    // ── register() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("returned ID starts with 'job-'")
        void idStartsWithJobPrefix() {
            String id = svc.register(OWNER, "download", "My Download");
            assertThat(id).startsWith("job-");
        }

        @Test
        @DisplayName("INSERT called with correct owner, job_type, and name")
        void insertCalledWithOwner() {
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.register(OWNER, "research", "Deep Research");
            verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
            Object[] args = cap.getValue();
            assertThat(args[1]).isEqualTo(OWNER);
            assertThat(args[2]).isEqualTo("research");
            assertThat(args[3]).isEqualTo("Deep Research");
        }

        @Test
        @DisplayName("status is set to 'running' on insert")
        void statusIsRunning() {
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.register(OWNER, "sync", "Connector Sync");
            verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
            assertThat(cap.getValue()[4]).isEqualTo("running");
        }

        @Test
        @DisplayName("progress is set to 0 on insert")
        void initialProgressIsZero() {
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.register(OWNER, "email", "Email Poll");
            verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
            assertThat(cap.getValue()[5]).isEqualTo(0);
        }
    }

    // ── updateProgress() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateProgress()")
    class UpdateProgress {

        @Test
        @DisplayName("calls UPDATE with progress value and message")
        void updatesProgressAndMsg() {
            svc.updateProgress("job-123", 42, "Processing...");
            verify(db).update(contains("UPDATE background_jobs SET progress"), eq(42), eq("Processing..."), eq("job-123"));
        }
    }

    // ── complete() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("complete()")
    class Complete {

        @Test
        @DisplayName("calls UPDATE with status='completed' and progress=100")
        void setsCompletedStatus() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("{\"result\":true}");
            svc.complete("job-abc", Map.of("result", true));
            verify(db).update(contains("status='completed'"), any(), any(), any());
        }

        @Test
        @DisplayName("removes thread from running map — cancel after complete returns false")
        void removesThreadFromMap() {
            String id = svc.register(OWNER, "t", "T");
            Thread mockThread = mock(Thread.class);
            svc.registerThread(id, mockThread);
            svc.complete(id, null);
            // After complete, the thread is removed — cancel should return false (no thread)
            boolean result = svc.cancel(id);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("null result does not throw (no mapper call needed)")
        void nullResultNoException() {
            assertThatCode(() -> svc.complete("job-xyz", null)).doesNotThrowAnyException();
        }
    }

    // ── fail() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fail()")
    class Fail {

        @Test
        @DisplayName("calls UPDATE with status='error' and error message")
        void setsErrorStatus() {
            svc.fail("job-123", "Connection refused");
            verify(db).update(contains("status='error'"), eq("Connection refused"), any(), eq("job-123"));
        }

        @Test
        @DisplayName("removes thread from map after fail")
        void removesThreadFromMap() {
            String id = svc.register(OWNER, "t", "T");
            Thread t = mock(Thread.class);
            svc.registerThread(id, t);
            svc.fail(id, "error");
            // Thread removed — cancel returns false
            assertThat(svc.cancel(id)).isFalse();
        }
    }

    // ── cancel() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("returns false and still calls UPDATE when no thread registered")
        void returnsFalseWhenNoThread() {
            String id = svc.register(OWNER, "t", "Test");
            boolean result = svc.cancel(id);
            assertThat(result).isFalse();
            verify(db).update(contains("status='cancelled'"), any(), any());
        }

        @Test
        @DisplayName("returns true and interrupts thread when thread is registered")
        void interruptsThread() {
            String id = svc.register(OWNER, "t", "Test");
            Thread mockThread = mock(Thread.class);
            svc.registerThread(id, mockThread);
            boolean result = svc.cancel(id);
            assertThat(result).isTrue();
            verify(mockThread).interrupt();
        }

        @Test
        @DisplayName("always calls DB UPDATE with status='cancelled'")
        void alwaysUpdatesDbStatus() {
            String id = svc.register(OWNER, "t", "Test");
            svc.cancel(id);
            verify(db).update(contains("status='cancelled'"), any(), any());
        }
    }

    // ── listActive() / listByOwner() / getById() ──────────────────────────────

    @Nested
    @DisplayName("listActive() / listByOwner() / getById()")
    class Query {

        @Test
        @DisplayName("listActive() queries for status='running'")
        void listActiveQueriesRunning() {
            when(db.queryForList(contains("status='running'"))).thenReturn(List.of());
            svc.listActive();
            verify(db).queryForList(contains("status='running'"));
        }

        @Test
        @DisplayName("listByOwner() passes owner and positive limit directly")
        void listByOwnerPositiveLimit() {
            when(db.queryForList(anyString(), eq(OWNER), eq(10))).thenReturn(List.of());
            svc.listByOwner(OWNER, 10);
            verify(db).queryForList(anyString(), eq(OWNER), eq(10));
        }

        @Test
        @DisplayName("listByOwner() defaults limit to 20 when limit <= 0")
        void listByOwnerDefaultsLimitTo20() {
            when(db.queryForList(anyString(), eq(OWNER), eq(20))).thenReturn(List.of());
            svc.listByOwner(OWNER, 0);
            verify(db).queryForList(anyString(), eq(OWNER), eq(20));
        }

        @Test
        @DisplayName("getById() returns null when DB returns no rows")
        void getByIdReturnsNullWhenEmpty() {
            when(db.queryForList(anyString(), eq("job-missing"))).thenReturn(List.of());
            assertThat(svc.getById("job-missing")).isNull();
        }

        @Test
        @DisplayName("getById() returns first row when DB has a match")
        void getByIdReturnsRow() {
            var row = Map.<String, Object>of("id", "job-1", "owner", OWNER, "status", "running");
            when(db.queryForList(anyString(), eq("job-1"))).thenReturn(List.of(row));
            assertThat(svc.getById("job-1")).isEqualTo(row);
        }
    }
}
