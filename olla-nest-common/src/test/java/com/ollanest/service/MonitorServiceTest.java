package com.ollanest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link MonitorService}.
 *
 * <p>No mocks needed — MonitorService is fully in-memory.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MonitorService — unit tests")
class MonitorServiceTest {

    // ── incrementRequests() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("incrementRequests()")
    class IncrementRequests {

        @Test
        @DisplayName("getSnapshot() shows increased request count")
        void requestCountIncreases() {
            MonitorService monitor = new MonitorService();
            monitor.incrementRequests();
            monitor.incrementRequests();
            Map<String, Object> snap = monitor.getSnapshot();
            assertThat((Long) snap.get("requests")).isEqualTo(2L);
        }
    }

    // ── incrementErrors() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("incrementErrors()")
    class IncrementErrors {

        @Test
        @DisplayName("getSnapshot() shows increased error count")
        void errorCountIncreases() {
            MonitorService monitor = new MonitorService();
            monitor.incrementErrors();
            Map<String, Object> snap = monitor.getSnapshot();
            assertThat((Long) snap.get("errors")).isEqualTo(1L);
        }
    }

    // ── getSnapshot() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSnapshot()")
    class GetSnapshot {

        @Test
        @DisplayName("returns map with uptime, requests, errors, memory keys")
        void containsAllKeys() {
            MonitorService monitor = new MonitorService();
            Map<String, Object> snap = monitor.getSnapshot();
            assertThat(snap).containsKeys("uptimeMs", "requests", "errors", "memoryUsedMb", "memoryTotalMb");
        }

        @Test
        @DisplayName("uptimeMs is non-negative")
        void uptimeNonNegative() {
            MonitorService monitor = new MonitorService();
            Map<String, Object> snap = monitor.getSnapshot();
            assertThat((Long) snap.get("uptimeMs")).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("requests starts at 0")
        void requestsStartsAtZero() {
            MonitorService monitor = new MonitorService();
            assertThat((Long) monitor.getSnapshot().get("requests")).isEqualTo(0L);
        }

        @Test
        @DisplayName("errors starts at 0")
        void errorsStartsAtZero() {
            MonitorService monitor = new MonitorService();
            assertThat((Long) monitor.getSnapshot().get("errors")).isEqualTo(0L);
        }
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("two concurrent increments result in 2 total requests")
        void concurrentIncrements() throws Exception {
            MonitorService monitor = new MonitorService();
            CountDownLatch latch = new CountDownLatch(2);
            ExecutorService exec = Executors.newFixedThreadPool(2);

            exec.submit(() -> { monitor.incrementRequests(); latch.countDown(); });
            exec.submit(() -> { monitor.incrementRequests(); latch.countDown(); });
            latch.await();
            exec.shutdown();

            assertThat((Long) monitor.getSnapshot().get("requests")).isEqualTo(2L);
        }
    }
}
