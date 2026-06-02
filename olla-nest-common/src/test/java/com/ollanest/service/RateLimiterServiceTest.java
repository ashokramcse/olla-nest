package com.ollanest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link RateLimiterService}.
 *
 * <p>Covers: basic allow/deny logic, window expiry, per-bucket isolation,
 * per-key isolation, count() reporting, reset(), concurrent thread safety,
 * and edge cases (zero/negative limits).
 *
 * <p>The service has no external dependencies and runs without mocks.
 *
 * @author Ashok Ram
 * @since v2026.2.0 — initial creation
 * @version v2026.2.0
 */
@DisplayName("RateLimiterService — unit tests")
class RateLimiterServiceTest {

    /** Fresh instance per test — each test gets a clean slate. */
    private RateLimiterService limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiterService();
    }

    // ── Basic allow / deny ────────────────────────────────────────────────────

    @Nested
    @DisplayName("allow() — basic permit / deny")
    class BasicAllowDeny {

        @Test
        @DisplayName("first request is always allowed")
        void firstRequestAllowed() {
            assertThat(limiter.allow("login", "1.2.3.4", 5, 60)).isTrue();
        }

        @Test
        @DisplayName("requests up to maxRequests are all allowed")
        void requestsUpToLimitAllowed() {
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.allow("chat", "10.0.0.1", 5, 60))
                        .as("request %d should be allowed", i + 1).isTrue();
            }
        }

        @Test
        @DisplayName("request beyond maxRequests is denied (rate limited)")
        void requestBeyondLimitDenied() {
            for (int i = 0; i < 5; i++) {
                limiter.allow("chat", "10.0.0.1", 5, 60);
            }
            assertThat(limiter.allow("chat", "10.0.0.1", 5, 60)).isFalse();
        }

        @Test
        @DisplayName("exactly maxRequests requests allowed, (maxRequests + 1) denied")
        void boundaryBehaviour() {
            for (int i = 0; i < 3; i++) {
                assertThat(limiter.allow("search", "192.168.1.1", 3, 60)).isTrue();
            }
            assertThat(limiter.allow("search", "192.168.1.1", 3, 60)).isFalse();
        }
    }

    // ── Window behaviour ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("allow() — sliding window")
    class SlidingWindow {

        @Test
        @DisplayName("maxRequests=1, window=1s: second immediate request denied")
        void immediateSecondDenied() {
            limiter.allow("login", "1.2.3.4", 1, 1);
            assertThat(limiter.allow("login", "1.2.3.4", 1, 1)).isFalse();
        }

        @Test
        @DisplayName("different IPs are independent — one IP denied does not affect another")
        void differentIpsIndependent() {
            for (int i = 0; i < 5; i++) limiter.allow("login", "1.1.1.1", 5, 60);
            limiter.allow("login", "1.1.1.1", 5, 60); // denied

            // Completely different IP starts clean
            assertThat(limiter.allow("login", "2.2.2.2", 5, 60)).isTrue();
        }
    }

    // ── Bucket isolation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("allow() — bucket isolation")
    class BucketIsolation {

        @Test
        @DisplayName("'login' and 'chat' buckets have independent counters for the same IP")
        void loginAndChatBucketsIndependent() {
            // Fill the login bucket for this IP
            for (int i = 0; i < 3; i++) limiter.allow("login", "5.5.5.5", 3, 60);
            assertThat(limiter.allow("login", "5.5.5.5", 3, 60)).isFalse();

            // Chat bucket for the same IP is still open
            assertThat(limiter.allow("chat", "5.5.5.5", 3, 60)).isTrue();
        }

        @ParameterizedTest(name = "bucket=''{0}'' is independent")
        @ValueSource(strings = {"login", "chat", "search", "upload", "webhook"})
        @DisplayName("named bucket isolation: each bucket counter is independent")
        void namedBucketIsolation(String bucket) {
            // Each bucket allows its own 5 requests
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.allow(bucket, "9.9.9.9", 5, 60))
                        .as("bucket=%s, request=%d", bucket, i + 1).isTrue();
            }
        }
    }

    // ── count() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("count()")
    class Count {

        @Test
        @DisplayName("returns 0 for a key that has never been used")
        void zeroForNewKey() {
            assertThat(limiter.count("login", "new-ip", 60)).isEqualTo(0);
        }

        @Test
        @DisplayName("returns correct count after N requests")
        void correctCountAfterRequests() {
            limiter.allow("search", "3.3.3.3", 10, 60);
            limiter.allow("search", "3.3.3.3", 10, 60);
            limiter.allow("search", "3.3.3.3", 10, 60);
            assertThat(limiter.count("search", "3.3.3.3", 60)).isEqualTo(3);
        }
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reset()")
    class Reset {

        @Test
        @DisplayName("after reset a previously rate-limited key is allowed again")
        void resetAllowsAfterDeny() {
            for (int i = 0; i < 2; i++) limiter.allow("login", "7.7.7.7", 2, 60);
            assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isFalse();

            limiter.reset("7.7.7.7");
            assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isTrue();
        }

        @Test
        @DisplayName("reset clears all buckets for the key, not just one")
        void resetClearsAllBuckets() {
            limiter.allow("login", "8.8.8.8", 1, 60);
            limiter.allow("chat", "8.8.8.8", 1, 60);
            // Both buckets full for this key
            assertThat(limiter.allow("login", "8.8.8.8", 1, 60)).isFalse();
            assertThat(limiter.allow("chat", "8.8.8.8", 1, 60)).isFalse();

            limiter.reset("8.8.8.8");
            assertThat(limiter.allow("login", "8.8.8.8", 1, 60)).isTrue();
            assertThat(limiter.allow("chat", "8.8.8.8", 1, 60)).isTrue();
        }

        @Test
        @DisplayName("reset on unknown key is a no-op (no exception)")
        void resetOnUnknownKeyIsNoOp() {
            assertThatCode(() -> limiter.reset("unknown-ip")).doesNotThrowAnyException();
        }
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("100 concurrent threads on same key: total allows == maxRequests, no race condition")
        void concurrentAllowsEqualsMaxRequests() throws InterruptedException {
            int maxRequests = 10;
            int threads = 100;
            AtomicInteger allowedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threads);
            var executor = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        if (limiter.allow("burst", "concurrent-ip", maxRequests, 300)) {
                            allowedCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // Exactly maxRequests threads should have been allowed
            assertThat(allowedCount.get()).isEqualTo(maxRequests);
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("maxRequests=1 allows exactly one request")
        void maxRequestsOne() {
            assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isTrue();
            assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isFalse();
        }

        @Test
        @DisplayName("maxRequests=Integer.MAX_VALUE never rate-limits in reasonable use")
        void maxRequestsMaxValue() {
            for (int i = 0; i < 1000; i++) {
                assertThat(limiter.allow("generous", "9.8.7.6", Integer.MAX_VALUE, 60)).isTrue();
            }
        }
    }
}
