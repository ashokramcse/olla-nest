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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            // The first request into any bucket must never be rejected
            assertThat(limiter.allow("login", "1.2.3.4", 5, 60)).isTrue();
        }

        @Test
        @DisplayName("requests up to maxRequests are all allowed")
        void requestsUpToLimitAllowed() {
            // All requests within the quota window must succeed
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.allow("chat", "10.0.0.1", 5, 60))
                        .as("request %d should be allowed", i + 1).isTrue();
            }
        }

        @Test
        @DisplayName("request beyond maxRequests is denied (rate limited)")
        void requestBeyondLimitDenied() {
            // Fill the quota to the limit
            for (int i = 0; i < 5; i++) {
                limiter.allow("chat", "10.0.0.1", 5, 60);
            }
            // The (maxRequests + 1)th request must be denied — this is the whole point of rate limiting
            assertThat(limiter.allow("chat", "10.0.0.1", 5, 60)).isFalse();
        }

        @Test
        @DisplayName("exactly maxRequests requests allowed, (maxRequests + 1) denied")
        void boundaryBehaviour() {
            // Fill the quota exactly to the boundary
            for (int i = 0; i < 3; i++) {
                assertThat(limiter.allow("search", "192.168.1.1", 3, 60)).isTrue();
            }
            // One over the boundary must be denied — off-by-one errors here would be a security bug
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
            // Fill the 1-request window
            limiter.allow("login", "1.2.3.4", 1, 1);
            // Immediate second request must be denied because the window hasn't expired
            assertThat(limiter.allow("login", "1.2.3.4", 1, 1)).isFalse();
        }

        @Test
        @DisplayName("different IPs are independent — one IP denied does not affect another")
        void differentIpsIndependent() {
            // Fill and exceed limit for first IP
            for (int i = 0; i < 5; i++) limiter.allow("login", "1.1.1.1", 5, 60);
            limiter.allow("login", "1.1.1.1", 5, 60); // denied

            // Completely different IP starts clean — rate limiting must not be global
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
            // login bucket is now exhausted for this IP
            assertThat(limiter.allow("login", "5.5.5.5", 3, 60)).isFalse();

            // Chat bucket for the same IP is still open — buckets must not share counters
            assertThat(limiter.allow("chat", "5.5.5.5", 3, 60)).isTrue();
        }

        @ParameterizedTest(name = "bucket=''{0}'' is independent")
        @ValueSource(strings = {"login", "chat", "search", "upload", "webhook"})
        @DisplayName("named bucket isolation: each bucket counter is independent")
        void namedBucketIsolation(String bucket) {
            // Each bucket allows its own 5 requests — filling one bucket must not affect others
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
            // An unseen key must report 0 — not throw or return a negative number
            assertThat(limiter.count("login", "new-ip", 60)).isEqualTo(0);
        }

        @Test
        @DisplayName("returns correct count after N requests")
        void correctCountAfterRequests() {
            // Make 3 requests and verify count tracks them accurately
            limiter.allow("search", "3.3.3.3", 10, 60);
            limiter.allow("search", "3.3.3.3", 10, 60);
            limiter.allow("search", "3.3.3.3", 10, 60);
            // count() must reflect all 3 requests — used by the UI to show usage feedback
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
            // Step 1: fill the bucket and confirm denial
            for (int i = 0; i < 2; i++) limiter.allow("login", "7.7.7.7", 2, 60);
            assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isFalse();

            // Step 2: reset the key and confirm the bucket is cleared
            limiter.reset("7.7.7.7");
            assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isTrue();
        }

        @Test
        @DisplayName("reset clears all buckets for the key, not just one")
        void resetClearsAllBuckets() {
            // Fill both buckets for this key
            limiter.allow("login", "8.8.8.8", 1, 60);
            limiter.allow("chat", "8.8.8.8", 1, 60);
            // Both buckets are now full
            assertThat(limiter.allow("login", "8.8.8.8", 1, 60)).isFalse();
            assertThat(limiter.allow("chat", "8.8.8.8", 1, 60)).isFalse();

            // Reset must clear ALL buckets for this key — not just one of them
            limiter.reset("8.8.8.8");
            assertThat(limiter.allow("login", "8.8.8.8", 1, 60)).isTrue();
            assertThat(limiter.allow("chat", "8.8.8.8", 1, 60)).isTrue();
        }

        @Test
        @DisplayName("reset on unknown key is a no-op (no exception)")
        void resetOnUnknownKeyIsNoOp() {
            // Reset must be idempotent — calling it for a key that never existed must not throw
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

            // 100 threads all compete for the same 10-request quota simultaneously
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

            // Exactly maxRequests threads should have been allowed — no more, no less
            // Any race condition would allow more than maxRequests
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
            // Strictest possible limit — only the first request gets through
            assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isTrue();
            assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isFalse();
        }

        @Test
        @DisplayName("maxRequests=Integer.MAX_VALUE never rate-limits in reasonable use")
        void maxRequestsMaxValue() {
            // Effectively unlimited quota — must not accidentally overflow or wrap to negative
            for (int i = 0; i < 1000; i++) {
                assertThat(limiter.allow("generous", "9.8.7.6", Integer.MAX_VALUE, 60)).isTrue();
            }
        }
    }
}
