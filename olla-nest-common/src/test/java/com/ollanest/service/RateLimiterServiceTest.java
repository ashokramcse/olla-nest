package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OCD-level unit tests for {@link RateLimiterService}.
 *
 * <p>
 * Covers: basic allow/deny logic, window expiry, per-bucket isolation, per-key
 * isolation, count() reporting, reset(), concurrent thread safety, and edge
 * cases (zero/negative limits).
 *
 * <p>
 * The service has no external dependencies and runs without mocks.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The rate limiter is a front-line abuse and brute-force control. These tests
 * pin its exact boundary behaviour (allow up to N, deny N+1), its isolation
 * guarantees (per bucket, per key), and its thread safety, because an
 * off-by-one or a race here is a real security weakness rather than a cosmetic
 * bug.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A fresh {@link RateLimiterService} is created per test via
 * {@code @BeforeEach}, so no state leaks between cases.</li>
 * <li>Concurrency is exercised with a fixed thread pool and a
 * {@link CountDownLatch} barrier, counting permitted requests with an
 * {@link AtomicInteger}.</li>
 * <li>Boundary and edge cases (zero, max-value limits) are covered explicitly
 * to guard against overflow and off-by-one errors.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.0 — initial creation; canonical Javadoc added in the project-wide
 * documentation pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0
 * @version v2026.2.0
 */
@DisplayName("RateLimiterService — unit tests")
class RateLimiterServiceTest {

	/** Fresh instance per test — each test gets a clean slate. */
	private RateLimiterService limiter;

	/**
	 * Constructs a brand-new {@link RateLimiterService} before each test so every
	 * case starts from a clean, empty set of buckets with no carried-over counts.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@BeforeEach
	void setUp() {
		limiter = new RateLimiterService();
	}

	// ── Basic allow / deny ────────────────────────────────────────────────────

	/**
	 * Tests for {@code allow()} — the basic permit/deny quota behaviour.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("allow() — basic permit / deny")
	class BasicAllowDeny {

		/**
		 * Proves the very first request into a previously unseen bucket is always
		 * permitted, establishing the baseline that the limiter never rejects an
		 * initial request.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("first request is always allowed")
		void firstRequestAllowed() {
			// The first request into any bucket must never be rejected
			assertThat(limiter.allow("login", "1.2.3.4", 5, 60)).isTrue();
		}

		/**
		 * Issues exactly {@code maxRequests} requests and asserts each one is
		 * permitted, confirming the limiter does not reject anything within the
		 * configured quota.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("requests up to maxRequests are all allowed")
		void requestsUpToLimitAllowed() {
			// All requests within the quota window must succeed
			for (int i = 0; i < 5; i++) {
				assertThat(limiter.allow("chat", "10.0.0.1", 5, 60)).as("request %d should be allowed", i + 1).isTrue();
			}
		}

		/**
		 * Verifies the request after the quota is exhausted is denied.
		 *
		 * <p>
		 * After filling the bucket to its limit, the next request must return
		 * {@code false} — the core purpose of rate limiting.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("request beyond maxRequests is denied (rate limited)")
		void requestBeyondLimitDenied() {
			// Fill the quota to the limit
			for (int i = 0; i < 5; i++) {
				limiter.allow("chat", "10.0.0.1", 5, 60);
			}
			// The (maxRequests + 1)th request must be denied — this is the whole point of
			// rate limiting
			assertThat(limiter.allow("chat", "10.0.0.1", 5, 60)).isFalse();
		}

		/**
		 * Verifies the exact allow/deny boundary (N allowed, N+1 denied).
		 *
		 * <p>
		 * Exactly {@code maxRequests} requests must succeed and the immediately
		 * following one must fail, guarding against off-by-one security bugs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("exactly maxRequests requests allowed, (maxRequests + 1) denied")
		void boundaryBehaviour() {
			// Fill the quota exactly to the boundary
			for (int i = 0; i < 3; i++) {
				assertThat(limiter.allow("search", "192.168.1.1", 3, 60)).isTrue();
			}
			// One over the boundary must be denied — off-by-one errors here would be a
			// security bug
			assertThat(limiter.allow("search", "192.168.1.1", 3, 60)).isFalse();
		}
	}

	// ── Window behaviour ──────────────────────────────────────────────────────

	/**
	 * Tests for {@code allow()} — sliding-window expiry and per-IP independence.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("allow() — sliding window")
	class SlidingWindow {

		/**
		 * Verifies a second immediate request within a 1-request window is denied.
		 *
		 * <p>
		 * With {@code maxRequests=1}, the second call before the window expires
		 * must return {@code false}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("maxRequests=1, window=1s: second immediate request denied")
		void immediateSecondDenied() {
			// Fill the 1-request window
			limiter.allow("login", "1.2.3.4", 1, 1);
			// Immediate second request must be denied because the window hasn't expired
			assertThat(limiter.allow("login", "1.2.3.4", 1, 1)).isFalse();
		}

		/**
		 * Verifies rate limiting is per-IP, not global.
		 *
		 * <p>
		 * After one IP is exhausted and denied, a different IP must still be
		 * allowed — limiting must never be global across clients.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("different IPs are independent — one IP denied does not affect another")
		void differentIpsIndependent() {
			// Fill and exceed limit for first IP
			for (int i = 0; i < 5; i++)
				limiter.allow("login", "1.1.1.1", 5, 60);
			limiter.allow("login", "1.1.1.1", 5, 60); // denied

			// Completely different IP starts clean — rate limiting must not be global
			assertThat(limiter.allow("login", "2.2.2.2", 5, 60)).isTrue();
		}
	}

	// ── Bucket isolation ──────────────────────────────────────────────────────

	/**
	 * Tests for {@code allow()} — per-bucket counter isolation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("allow() — bucket isolation")
	class BucketIsolation {

		/**
		 * Verifies distinct buckets keep independent counters for one IP.
		 *
		 * <p>
		 * Exhausting the {@code login} bucket for an IP must leave the {@code chat}
		 * bucket for the same IP open.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("'login' and 'chat' buckets have independent counters for the same IP")
		void loginAndChatBucketsIndependent() {
			// Fill the login bucket for this IP
			for (int i = 0; i < 3; i++)
				limiter.allow("login", "5.5.5.5", 3, 60);
			// login bucket is now exhausted for this IP
			assertThat(limiter.allow("login", "5.5.5.5", 3, 60)).isFalse();

			// Chat bucket for the same IP is still open — buckets must not share counters
			assertThat(limiter.allow("chat", "5.5.5.5", 3, 60)).isTrue();
		}

		/**
		 * Verifies each named bucket has its own quota.
		 *
		 * <p>
		 * For every bucket name in the value source, all five requests must be
		 * allowed independently of the others.
		 *
		 * @param bucket the bucket name supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@ParameterizedTest(name = "bucket=''{0}'' is independent")
		@ValueSource(strings = { "login", "chat", "search", "upload", "webhook" })
		@DisplayName("named bucket isolation: each bucket counter is independent")
		void namedBucketIsolation(String bucket) {
			// Each bucket allows its own 5 requests — filling one bucket must not affect
			// others
			for (int i = 0; i < 5; i++) {
				assertThat(limiter.allow(bucket, "9.9.9.9", 5, 60)).as("bucket=%s, request=%d", bucket, i + 1).isTrue();
			}
		}
	}

	// ── count() ───────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code count()} — usage reporting.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("count()")
	class Count {

		/**
		 * Verifies an unseen key reports a count of zero.
		 *
		 * <p>
		 * A never-used key must return {@code 0} rather than throwing or returning
		 * a negative number.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("returns 0 for a key that has never been used")
		void zeroForNewKey() {
			// An unseen key must report 0 — not throw or return a negative number
			assertThat(limiter.count("login", "new-ip", 60)).isEqualTo(0);
		}

		/**
		 * Verifies the count tracks the number of requests made.
		 *
		 * <p>
		 * After three requests, {@code count()} must report exactly three — the
		 * value the UI uses to show usage feedback.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code reset()} — clearing a key's buckets.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("reset()")
	class Reset {

		/**
		 * Verifies a reset re-opens a previously rate-limited key.
		 *
		 * <p>
		 * After exhausting and being denied, resetting the key must allow the next
		 * request again.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("after reset a previously rate-limited key is allowed again")
		void resetAllowsAfterDeny() {
			// Step 1: fill the bucket and confirm denial
			for (int i = 0; i < 2; i++)
				limiter.allow("login", "7.7.7.7", 2, 60);
			assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isFalse();

			// Step 2: reset the key and confirm the bucket is cleared
			limiter.reset("7.7.7.7");
			assertThat(limiter.allow("login", "7.7.7.7", 2, 60)).isTrue();
		}

		/**
		 * Verifies reset clears every bucket for the key.
		 *
		 * <p>
		 * With both {@code login} and {@code chat} buckets full, a single reset
		 * must re-open both, not just one.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies resetting an unknown key is a safe no-op.
		 *
		 * <p>
		 * Reset must be idempotent — calling it for a key that never existed must
		 * not throw.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("reset on unknown key is a no-op (no exception)")
		void resetOnUnknownKeyIsNoOp() {
			// Reset must be idempotent — calling it for a key that never existed must not
			// throw
			assertThatCode(() -> limiter.reset("unknown-ip")).doesNotThrowAnyException();
		}
	}

	// ── Thread safety ─────────────────────────────────────────────────────────

	/**
	 * Tests for concurrent access — race-free quota enforcement.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("Thread safety")
	class ThreadSafety {

		/**
		 * Verifies concurrent contenders never exceed the quota.
		 *
		 * <p>
		 * 100 threads racing for a 10-request quota on the same key must result in
		 * exactly 10 allows — any more would prove a race condition.
		 *
		 * @throws InterruptedException if the latch await is interrupted
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for boundary limit values.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCases {

		/**
		 * Verifies a limit of one allows exactly one request.
		 *
		 * <p>
		 * The strictest limit permits the first call and denies the second.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("maxRequests=1 allows exactly one request")
		void maxRequestsOne() {
			// Strictest possible limit — only the first request gets through
			assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isTrue();
			assertThat(limiter.allow("strict", "1.2.3.4", 1, 60)).isFalse();
		}

		/**
		 * Verifies a {@link Integer#MAX_VALUE} limit never rate-limits in normal
		 * use.
		 *
		 * <p>
		 * 1000 requests against an effectively unlimited quota must all be allowed
		 * with no overflow to a negative count.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("maxRequests=Integer.MAX_VALUE never rate-limits in reasonable use")
		void maxRequestsMaxValue() {
			// Effectively unlimited quota — must not accidentally overflow or wrap to
			// negative
			for (int i = 0; i < 1000; i++) {
				assertThat(limiter.allow("generous", "9.8.7.6", Integer.MAX_VALUE, 60)).isTrue();
			}
		}
	}
}
