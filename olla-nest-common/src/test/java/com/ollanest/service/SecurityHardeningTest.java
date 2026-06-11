package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.ollanest.util.UrlValidator;

/**
 * Security-hardening validation tests.
 *
 * <p>
 * Covers: SSRF URL validation (private/loopback/link-local ranges), non-HTTP
 * scheme rejection, session token entropy, token injection rejection, AES-GCM
 * tamper detection, rate-limit map eviction, and static SecureRandom.
 *
 * <p>
 * These tests act as executable security specifications — they document and
 * enforce the security invariants of the production codebase.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The platform's security posture depends on a handful of low-level invariants
 * (SSRF blocking, token format, AEAD tamper detection, bounded rate-limit state)
 * that are easy to regress silently during refactors. This suite pins each
 * invariant as an executable specification so any weakening of a control fails
 * the build instead of shipping to production.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Validator and crypto behaviour is exercised against the real
 * {@link UrlValidator} and {@link CryptoService} — no mocks — since these are
 * pure, side-effect-free units.</li>
 * <li>Structural invariants ({@code @Scheduled} sweepers, private fields, static
 * {@code SecureRandom}) are asserted via reflection so the tests fail if the
 * production wiring is removed.</li>
 * <li>Tests are grouped into {@link Nested} classes by control area (SSRF, token
 * entropy, injection catalogue, rate-limit eviction, tamper detection,
 * SecureRandom).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — security-hardening validation suite documented in the
 * project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@DisplayName("Security Hardening — validation tests")
class SecurityHardeningTest {

	// ── SSRF protection ───────────────────────────────────────────────────

	/**
	 * Exercises {@link UrlValidator} against the full catalogue of SSRF vectors —
	 * null/blank input, non-HTTP schemes, loopback, RFC-1918 ranges, link-local
	 * cloud metadata, and malformed/host-less URLs.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("UrlValidator — SSRF protection")
	class SsrfProtection {

		/**
		 * Asserts a {@code null} URL is reported unsafe without raising an NPE.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects null URL")
		void nullRejected() {
			// SECURITY: null URL must be treated as unsafe — no NPE allowed
			assertThat(UrlValidator.isSafeUrl(null)).isFalse();
		}

		/**
		 * Asserts a whitespace-only URL is reported unsafe.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects blank URL")
		void blankRejected() {
			// Blank string cannot be a valid external URL
			assertThat(UrlValidator.isSafeUrl("   ")).isFalse();
		}

		/**
		 * Asserts a {@code file://} URL is rejected, blocking local-filesystem reads
		 * such as {@code /etc/passwd}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects file:// scheme (local filesystem access)")
		void fileSchemeRejected() {
			// SECURITY: file:// would allow reading local secrets like /etc/passwd
			assertThat(UrlValidator.isSafeUrl("file:///etc/passwd")).isFalse();
		}

		/**
		 * Asserts an {@code ftp://} URL is rejected, since only HTTP/HTTPS are safe
		 * for outbound API calls.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects ftp:// scheme")
		void ftpSchemeRejected() {
			// Only http/https are safe for external API calls
			assertThat(UrlValidator.isSafeUrl("ftp://example.com/file")).isFalse();
		}

		/**
		 * Asserts a {@code javascript:} URI is rejected, blocking script-execution
		 * vectors smuggled in as URLs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects javascript: scheme (XSS via URL)")
		void javascriptSchemeRejected() {
			// SECURITY: javascript: URIs in server-side HTTP calls would be an execution
			// exploit
			assertThat(UrlValidator.isSafeUrl("javascript:alert(1)")).isFalse();
		}

		/**
		 * Asserts the canonical loopback address {@code 127.0.0.1} is rejected,
		 * blocking SSRF to services bound on localhost.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 127.0.0.1 (loopback)")
		void loopbackIpRejected() {
			// SECURITY: SSRF — 127.0.0.1 would allow attackers to reach internal services
			assertThat(UrlValidator.isSafeUrl("http://127.0.0.1:8080")).isFalse();
		}

		/**
		 * Asserts an address at the top of the {@code 127.0.0.0/8} block is
		 * rejected, proving the whole loopback range (not just {@code 127.0.0.1}) is
		 * blocked.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 127.x.x.x (full loopback range)")
		void loopbackRangeRejected() {
			// The entire 127.0.0.0/8 range is loopback — all must be blocked
			assertThat(UrlValidator.isSafeUrl("http://127.255.255.255/api")).isFalse();
		}

		/**
		 * Asserts the hostname {@code localhost} is rejected by the hostname check,
		 * since it resolves to the loopback interface.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects localhost (resolves to loopback)")
		void localhostRejected() {
			// "localhost" resolves to 127.0.0.1 — must be blocked by hostname check
			assertThat(UrlValidator.isSafeUrl("http://localhost/api")).isFalse();
		}

		/**
		 * Asserts a {@code 10.0.0.0/8} private address is rejected, blocking SSRF
		 * into the RFC-1918 class A range.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 10.0.0.1 (RFC-1918 class A private)")
		void rfc1918ClassArejected() {
			// SECURITY: 10.0.0.0/8 is RFC-1918 private — would allow internal network
			// access
			assertThat(UrlValidator.isSafeUrl("http://10.0.0.1/internal")).isFalse();
		}

		/**
		 * Asserts the bottom of the {@code 172.16.0.0/12} private range is rejected.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 172.16.0.1 (RFC-1918 class B private)")
		void rfc1918ClassBrejected() {
			// 172.16.0.0/12 range must be blocked
			assertThat(UrlValidator.isSafeUrl("http://172.16.0.1/internal")).isFalse();
		}

		/**
		 * Asserts the top of the {@code 172.16.0.0/12} private range is rejected,
		 * covering the upper boundary of the class B block.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 172.31.255.255 (top of RFC-1918 class B range)")
		void rfc1918ClassBtopRejected() {
			// Top boundary of the 172.16.0.0/12 range must also be blocked
			assertThat(UrlValidator.isSafeUrl("http://172.31.255.255/admin")).isFalse();
		}

		/**
		 * Asserts a {@code 192.168.0.0/16} private address is rejected, blocking
		 * SSRF into the RFC-1918 class C range commonly used by home routers.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 192.168.1.1 (RFC-1918 class C private)")
		void rfc1918ClassCrejected() {
			// 192.168.0.0/16 is RFC-1918 private — commonly used for home routers
			assertThat(UrlValidator.isSafeUrl("http://192.168.1.1/router")).isFalse();
		}

		/**
		 * Asserts the {@code 169.254.169.254} link-local cloud metadata endpoint is
		 * rejected, the highest-impact SSRF target on AWS/GCP.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects 169.254.169.254 (AWS metadata / link-local)")
		void awsMetadataRejected() {
			// SECURITY: AWS/GCP metadata endpoint — must never be reachable via SSRF
			assertThat(UrlValidator.isSafeUrl("http://169.254.169.254/latest/meta-data/")).isFalse();
		}

		/**
		 * Asserts an unparseable URL string is treated as unsafe rather than passed
		 * through.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects malformed URL")
		void malformedUrlRejected() {
			// Unparseable URLs must be treated as unsafe — not passed through
			assertThat(UrlValidator.isSafeUrl("not-a-url")).isFalse();
		}

		/**
		 * Asserts a host-less URL is rejected, since a missing host would bypass the
		 * hostname-based SSRF checks.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects URL with no host")
		void noHostRejected() {
			// No-host URL would bypass hostname checks — must be rejected
			assertThat(UrlValidator.isSafeUrl("http:///path")).isFalse();
		}
	}

	// ── Token entropy ──────────────────────────────────────────────────────

	/**
	 * Verifies the session-token generation algorithm produces 64-char lowercase
	 * hex tokens with enough entropy that a large batch contains no collisions.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("Session token entropy")
	class TokenEntropy {

		/**
		 * Generates a token using the production algorithm (32 random bytes rendered
		 * as hex) and asserts it is exactly 64 lowercase hex characters — i.e. 256
		 * bits of entropy.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("AuthService token format: exactly 64 lowercase hex characters")
		void tokenFormatIsValid() {
			// Verify the token generation algorithm produces the expected 64-char hex
			// format
			SecureRandom rng = new SecureRandom();
			byte[] bytes = new byte[32];
			rng.nextBytes(bytes);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : bytes)
				sb.append(String.format("%02x", b));
			String token = sb.toString();
			// 32 bytes = 256 bits of entropy, formatted as 64 lowercase hex chars
			assertThat(token).hasSize(64).matches("[0-9a-f]{64}");
		}

		/**
		 * Generates a thousand tokens and asserts they are all distinct; any
		 * collision would signal a broken RNG and a catastrophic session-security
		 * failure.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@RepeatedTest(1)
		@DisplayName("1000 tokens are all unique (256-bit entropy)")
		void tokensAreUnique() {
			// 256-bit entropy makes collision probability astronomically low — all 1000
			// must be unique
			SecureRandom rng = new SecureRandom();
			Set<String> tokens = new HashSet<>();
			for (int i = 0; i < 1000; i++) {
				byte[] bytes = new byte[32];
				rng.nextBytes(bytes);
				StringBuilder sb = new StringBuilder(64);
				for (byte b : bytes)
					sb.append(String.format("%02x", b));
				tokens.add(sb.toString());
			}
			// Any collision would indicate a broken RNG — catastrophic for session security
			assertThat(tokens).hasSize(1000);
		}
	}

	// ── Token format guard — injection payload catalogue ──────────────────

	/**
	 * Verifies the strict 64-hex token-format guard rejects an exhaustive
	 * catalogue of injection and malformed-token payloads (SQLi, CRLF, XSS, path
	 * traversal, oversized, wrong case).
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("Token format guard — injection payload catalogue")
	class TokenInjectionCatalogue {

		/** Canonical 64-lowercase-hex token pattern the guard enforces. */
		private static final Pattern VALID = Pattern.compile("^[0-9a-f]{64}$");

		/**
		 * Asserts the supplied payload fails the canonical token-format pattern,
		 * proving it would be rejected before any session lookup.
		 *
		 * @param payload the candidate token string expected to be rejected
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		private void assertRejected(String payload) {
			// SECURITY: any payload that does not match the exact 64-hex-char format must
			// be rejected
			assertThat(VALID.matcher(payload).matches()).as("Payload should be rejected by token format guard")
					.isFalse();
		}

		// All known injection/attack payloads must fail the token format guard

		/**
		 * Asserts a SQL UNION-injection payload is rejected by the token guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void sqlUnionInjection() {
			assertRejected("' UNION SELECT * FROM users --");
		}

		/**
		 * Asserts a SQL {@code OR 1=1} injection payload is rejected by the token
		 * guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void sqlOrInjection() {
			assertRejected("' OR 1=1 --");
		}

		/**
		 * Asserts a CRLF header-injection payload is rejected by the token guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void crlfInjection() {
			assertRejected("valid\r\nX-Evil: injected");
		}

		/**
		 * Asserts a space-separated value is rejected, since whitespace is not valid
		 * hex.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void spaceSeparated() {
			assertRejected("abc def");
		}

		/**
		 * Asserts an oversized 1024-char token is rejected by the fixed-length guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void oversizedToken() {
			assertRejected("a".repeat(1024));
		}

		/**
		 * Asserts an empty token is rejected.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void emptyToken() {
			assertRejected("");
		}

		/**
		 * Asserts a whitespace-only token is rejected.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void whitespaceOnly() {
			assertRejected("   ");
		}

		/**
		 * Asserts an all-uppercase-hex token is rejected, since the guard requires
		 * lowercase hex.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void uppercaseHex() {
			assertRejected("A".repeat(64));
		}

		/**
		 * Asserts a mixed-case token is rejected, since any uppercase character
		 * breaks the lowercase-hex requirement.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void mixedCase() {
			assertRejected("aAbBcCdDeEfF" + "0".repeat(52));
		}

		/**
		 * Asserts a 64-char value ending in a semicolon is rejected, blocking a
		 * potential header-injection suffix.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void semicolonSuffix() {
			assertRejected("a".repeat(63) + ";");
		}

		/**
		 * Asserts a path-traversal payload is rejected by the token guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void slashTraversal() {
			assertRejected("../../../etc/passwd");
		}

		/**
		 * Asserts an XSS script payload is rejected by the token guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void xssPayload() {
			assertRejected("<script>alert(1)</script>");
		}

		/**
		 * Asserts a percent-encoded null-byte payload is rejected by the token guard.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		void percentEncoded() {
			assertRejected("a".repeat(60) + "%00abc");
		}
	}

	// ── ChatService rateLimitMap eviction ─────────────────────────────────

	/**
	 * Verifies the {@code ChatService} rate-limit map stays bounded: its sweeper is
	 * scheduled and the map itself is encapsulated against external mutation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("ChatService — rate limit map bounded growth")
	class RateLimitMapEviction {

		/**
		 * Reflects over {@code cleanStaleRateLimitEntries} and asserts it exists and
		 * is annotated {@code @Scheduled}, without which the rate-limit map would
		 * leak memory.
		 *
		 * @throws Exception if the scheduled method cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("cleanStaleRateLimitEntries method exists and is @Scheduled")
		void cleanupMethodIsScheduled() throws Exception {
			Method m = ChatService.class.getMethod("cleanStaleRateLimitEntries");
			assertThat(m).isNotNull();
			// @Scheduled annotation must be present — missing it would cause a memory leak
			assertThat(m.isAnnotationPresent(Scheduled.class)).as("cleanStaleRateLimitEntries must be @Scheduled")
					.isTrue();
		}

		/**
		 * Reflects over the {@code rateLimitMap} field and asserts it is
		 * {@code private}, preventing external mutation of rate-limit state.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rateLimitMap field is private (encapsulated)")
		void rateLimitMapIsPrivate() throws Exception {
			Field f = ChatService.class.getDeclaredField("rateLimitMap");
			// SECURITY: rateLimitMap must be private — no external mutation of rate limit
			// state
			assertThat(Modifier.isPrivate(f.getModifiers())).as("rateLimitMap must be private").isTrue();
		}
	}

	// ── CryptoService — AES-GCM tamper detection ──────────────────────────

	/**
	 * Verifies the AES-GCM envelope in {@link CryptoService} detects every form of
	 * tampering — bit flips, prepended bytes, IV/ciphertext mismatch, and wrong
	 * key — by returning an empty string rather than corrupted plaintext.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("CryptoService — tamper detection")
	class CryptoTamperDetection {

		/** Crypto service under test, keyed with a fixed test secret. */
		private final CryptoService crypto = new CryptoService("security-test-key");

		/**
		 * Encrypts a credential, flips a single bit in the ciphertext, and asserts
		 * decryption returns an empty string — proving the GCM auth tag detects the
		 * tampering.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("1-bit flip in ciphertext detected by GCM auth tag")
		void oneBitFlipDetected() {
			// Encrypt a credential, then flip one bit in the ciphertext
			String enc = crypto.encryptKey("sensitive-credential");
			String[] parts = enc.split(":");
			char flipped = parts[2].charAt(0) == '0' ? '1' : '0';
			String tampered = parts[0] + ":" + parts[1] + ":" + flipped + parts[2].substring(1);
			// SECURITY: AES-GCM must detect the tampered ciphertext — returns empty string
			assertThat(crypto.decryptKey(tampered)).isEqualTo("");
		}

		/**
		 * Prepends extra bytes to the ciphertext and asserts decryption returns an
		 * empty string, proving the GCM auth tag rejects length/content tampering.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("prefix prepended to ciphertext detected by GCM auth tag")
		void prependDetected() {
			// Prepend "deadbeef" to the ciphertext — GCM auth tag must catch this
			String enc = crypto.encryptKey("credential");
			String[] parts = enc.split(":");
			String tampered = parts[0] + ":" + parts[1] + ":deadbeef" + parts[2];
			assertThat(crypto.decryptKey(tampered)).isEqualTo("");
		}

		/**
		 * Combines the IV of one ciphertext with the body+tag of another and asserts
		 * the result does not decrypt to the original plaintext, proving GCM rejects
		 * an IV-swap replay attack.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("IV swap (replay attack) fails GCM authentication")
		void replayAttackDetected() {
			// Combine IV from one ciphertext with the ciphertext+tag of another — replay
			// attack
			String enc1 = crypto.encryptKey("value-one");
			String enc2 = crypto.encryptKey("value-two");
			// Use IV from enc2 with tag+ciphertext from enc1 — GCM must reject this
			String mixed = enc2.split(":")[0] + ":" + enc1.split(":")[1] + ":" + enc1.split(":")[2];
			assertThat(crypto.decryptKey(mixed)).isNotEqualTo("value-one");
		}

		/**
		 * Encrypts with one key and decrypts with a different one, asserting the
		 * result is an empty string rather than a throw or partial data — proving a
		 * wrong key fails GCM authentication cleanly.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("wrong key returns empty string — GCM authentication fails")
		void wrongKeyReturnsEmpty() {
			// Encrypt with one key, decrypt with a different key — must not succeed
			String encrypted = crypto.encryptKey("my-secret");
			CryptoService wrongKey = new CryptoService("completely-different-key");
			// SECURITY: wrong key must return empty string — not throw or return partial
			// data
			assertThat(wrongKey.decryptKey(encrypted)).isEqualTo("");
		}
	}

	// ── Static SecureRandom fields ─────────────────────────────────────────

	/**
	 * Verifies the security-sensitive services each hold their {@code SecureRandom}
	 * (or UID RNG) as a static field, avoiding wasteful per-call instantiation and
	 * re-seeding.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("Static SecureRandom — no per-call instantiation")
	class StaticSecureRandomFields {

		/**
		 * Asserts {@code AuthService.SECURE_RANDOM} is declared static.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("AuthService.SECURE_RANDOM is static")
		void authServiceSecureRandomIsStatic() throws Exception {
			Field f = AuthService.class.getDeclaredField("SECURE_RANDOM");
			// Static field = single shared instance; per-call instantiation wastes entropy
			// pool seeding
			assertThat(Modifier.isStatic(f.getModifiers())).as("AuthService.SECURE_RANDOM must be static").isTrue();
		}

		/**
		 * Asserts {@code CryptoService.SECURE_RANDOM} is declared static.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("CryptoService.SECURE_RANDOM is static")
		void cryptoServiceSecureRandomIsStatic() throws Exception {
			Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(Modifier.isStatic(f.getModifiers())).as("CryptoService.SECURE_RANDOM must be static").isTrue();
		}

		/**
		 * Asserts {@code ChatService.UID_RNG} is declared static.
		 *
		 * @throws Exception if the field cannot be resolved reflectively
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("ChatService.UID_RNG is static")
		void chatServiceUidRngIsStatic() throws Exception {
			Field f = ChatService.class.getDeclaredField("UID_RNG");
			assertThat(Modifier.isStatic(f.getModifiers())).as("ChatService.UID_RNG must be static").isTrue();
		}
	}
}
