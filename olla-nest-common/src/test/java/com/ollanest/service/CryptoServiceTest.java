package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CryptoService}.
 *
 * <p>
 * Covers: AES-GCM encrypt/decrypt round-trip, IV uniqueness (no IV reuse),
 * corrupt-ciphertext handling, wrong-key handling, null/blank input handling,
 * SecureRandom static-field reuse, and format validation.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link CryptoService} protects at-rest secrets (API keys). These tests assert
 * the cryptographic invariants that make that protection trustworthy: a clean
 * round-trip, semantic security via per-call IVs, and authenticated-encryption
 * tamper detection. A regression here would silently weaken confidentiality, so
 * the suite is deliberately exhaustive.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The service has no collaborators, so a single real {@link CryptoService}
 * instance is constructed directly — no mocks.</li>
 * <li>The serialized format is asserted segment-by-segment
 * ({@code iv:tag:ciphertext}) so format drift is caught immediately.</li>
 * <li>Tamper tests flip individual hex digits to confirm GCM authentication
 * rejects any modification.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — canonical Javadoc added in the project-wide documentation
 * pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@DisplayName("CryptoService — unit tests")
class CryptoServiceTest {

	/** Real crypto service under test, seeded with a deterministic test key. */
	private final CryptoService crypto = new CryptoService("test-encryption-key-for-junit");

	// ── Round-trip ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("encrypt / decrypt round-trip")
	class RoundTrip {

		/**
		 * Encrypts a plain ASCII secret and decrypts it back, proving the most basic
		 * round-trip invariant: {@code decrypt(encrypt(x)) == x}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("encrypts and decrypts a simple string")
		void simpleString() {
			String plaintext = "super-secret-api-key-12345";
			String encrypted = crypto.encryptKey(plaintext);
			assertThat(crypto.decryptKey(encrypted)).isEqualTo(plaintext);
		}

		/**
		 * Confirms the empty string is a valid payload that survives the round-trip
		 * unchanged, ensuring zero-length secrets are not special-cased into an error.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("round-trips an empty string")
		void emptyString() {
			String encrypted = crypto.encryptKey("");
			assertThat(crypto.decryptKey(encrypted)).isEqualTo("");
		}

		/**
		 * Round-trips a multibyte UTF-8 string (Japanese plus an emoji) to prove the
		 * encoding step preserves non-ASCII bytes exactly and does not corrupt
		 * surrogate pairs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("round-trips a Unicode string (multibyte UTF-8)")
		void unicodeString() {
			String plaintext = "日本語テスト🔑";
			assertThat(crypto.decryptKey(crypto.encryptKey(plaintext))).isEqualTo(plaintext);
		}

		/**
		 * Round-trips a 10 KB payload to confirm the cipher handles buffers well beyond
		 * a single block without truncation or buffering errors.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("round-trips a 10 KB payload")
		void largePayload() {
			String large = "x".repeat(10_240);
			assertThat(crypto.decryptKey(crypto.encryptKey(large))).isEqualTo(large);
		}

		/**
		 * Asserts the serialized form contains exactly two colons, pinning the
		 * three-segment {@code iv:tag:ciphertext} layout that {@code decryptKey}
		 * depends on when parsing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("encrypted output contains exactly two colons (iv:tag:ciphertext)")
		void outputHasTwoColons() {
			String enc = crypto.encryptKey("hello");
			long colons = enc.chars().filter(c -> c == ':').count();
			assertThat(colons).isEqualTo(2);
		}

		/**
		 * Verifies the IV segment is exactly 24 lowercase hex characters, i.e. the
		 * 12-byte nonce recommended for AES-GCM. A wrong-length IV would indicate a
		 * misconfigured cipher.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("IV segment is exactly 24 hex chars (12 bytes)")
		void ivIs24HexChars() {
			String enc = crypto.encryptKey("hello");
			String iv = enc.split(":")[0];
			assertThat(iv).hasSize(24).matches("[0-9a-f]+");
		}

		/**
		 * Verifies the authentication-tag segment is exactly 32 lowercase hex
		 * characters, i.e. the full 16-byte GCM tag. A truncated tag would weaken
		 * tamper detection.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("auth tag segment is exactly 32 hex chars (16 bytes)")
		void tagIs32HexChars() {
			String enc = crypto.encryptKey("hello");
			String tag = enc.split(":")[1];
			assertThat(tag).hasSize(32).matches("[0-9a-f]+");
		}
	}

	// ── IV uniqueness ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("IV uniqueness — no IV reuse across calls")
	class IvUniqueness {

		/**
		 * Encrypts 1000 distinct payloads and asserts all 1000 IVs are unique. IV reuse
		 * under a single key catastrophically breaks GCM, so this is a critical
		 * security invariant proving a fresh nonce is drawn per call.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@RepeatedTest(1)
		@DisplayName("1000 encrypt calls produce 1000 unique IVs (SecureRandom per call)")
		void noIvReuse() {
			Set<String> ivs = new HashSet<>();
			for (int i = 0; i < 1000; i++) {
				String enc = crypto.encryptKey("payload-" + i);
				ivs.add(enc.split(":")[0]);
			}
			assertThat(ivs).hasSize(1000);
		}

		/**
		 * Encrypts identical plaintext twice and asserts the two ciphertexts differ.
		 * This demonstrates semantic security: an observer cannot tell that the same
		 * secret was stored twice.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("same plaintext encrypted twice produces different ciphertexts (semantic security)")
		void samePlaintextDifferentCiphertext() {
			String a = crypto.encryptKey("identical");
			String b = crypto.encryptKey("identical");
			assertThat(a).isNotEqualTo(b);
		}
	}

	// ── Error handling ────────────────────────────────────────────────────

	@Nested
	@DisplayName("decryptKey — error handling")
	class ErrorHandling {

		/**
		 * Confirms decrypting {@code null} returns an empty string rather than throwing
		 * a {@link NullPointerException}, so callers can pass through unset values
		 * safely.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null input returns empty string (no exception)")
		void nullInput() {
			assertThat(crypto.decryptKey(null)).isEqualTo("");
		}

		/**
		 * Confirms a blank (whitespace-only) ciphertext decrypts to an empty string
		 * without error, treating it the same as an absent value.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("blank input returns empty string (no exception)")
		void blankInput() {
			assertThat(crypto.decryptKey("   ")).isEqualTo("");
		}

		/**
		 * Confirms input that does not contain the expected colon-delimited segments is
		 * rejected gracefully with an empty string instead of throwing an
		 * array-index error during parsing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("malformed input (not enough colons) returns empty string")
		void malformedInput() {
			assertThat(crypto.decryptKey("notvalid")).isEqualTo("");
		}

		/**
		 * Replaces the ciphertext segment with a short bogus value and confirms
		 * decryption returns an empty string because the GCM tag check fails. This
		 * proves corrupted ciphertext is never returned as if valid.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("truncated ciphertext returns empty string (tag check fails)")
		void truncatedCiphertext() {
			String enc = crypto.encryptKey("secret");
			// Corrupt the ciphertext segment
			String[] parts = enc.split(":");
			String corrupted = parts[0] + ":" + parts[1] + ":deadbeef";
			assertThat(crypto.decryptKey(corrupted)).isEqualTo("");
		}

		/**
		 * Encrypts with one key and attempts decryption with a different
		 * {@link CryptoService}, confirming the mismatch yields an empty string because
		 * GCM authentication fails. This proves data encrypted under one key cannot be
		 * read with another.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("wrong key returns empty string (GCM authentication fails)")
		void wrongKey() {
			String encrypted = crypto.encryptKey("my secret");
			CryptoService wrongKeyService = new CryptoService("completely-different-key");
			assertThat(wrongKeyService.decryptKey(encrypted)).isEqualTo("");
		}

		/**
		 * Flips a single hex digit inside the ciphertext body and confirms decryption
		 * returns an empty string, demonstrating that GCM detects even a one-character
		 * modification to the encrypted payload.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("bit-flip in ciphertext body returns empty string (GCM detects tampering)")
		void bitFlipInCiphertext() {
			String enc = crypto.encryptKey("tamper me");
			String[] parts = enc.split(":");
			if (parts[2].length() > 2) {
				// Flip one hex digit in the ciphertext
				char flipped = parts[2].charAt(0) == 'a' ? 'b' : 'a';
				String tampered = parts[0] + ":" + parts[1] + ":" + flipped + parts[2].substring(1);
				assertThat(crypto.decryptKey(tampered)).isEqualTo("");
			}
		}

		/**
		 * Flips a single hex digit inside the authentication-tag segment and confirms
		 * decryption returns an empty string, proving the tag itself is verified and
		 * cannot be tampered with independently of the ciphertext.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("bit-flip in auth tag returns empty string (GCM authentication)")
		void bitFlipInTag() {
			String enc = crypto.encryptKey("tamper tag");
			String[] parts = enc.split(":");
			char flipped = parts[1].charAt(0) == 'a' ? 'b' : 'a';
			String tampered = parts[0] + ":" + flipped + parts[1].substring(1) + ":" + parts[2];
			assertThat(crypto.decryptKey(tampered)).isEqualTo("");
		}
	}

	// ── Static SecureRandom ───────────────────────────────────────────────

	@Nested
	@DisplayName("SECURE_RANDOM static field")
	class StaticSecureRandom {

		/**
		 * Uses reflection to assert {@code SECURE_RANDOM} is declared {@code static}.
		 * A static {@link SecureRandom} avoids the per-call seeding cost that would
		 * otherwise drain entropy and slow every encrypt call.
		 *
		 * @throws Exception if the reflective field lookup fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("SECURE_RANDOM is a static field — no per-call instantiation")
		void secureRandomIsStatic() throws Exception {
			Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(Modifier.isStatic(f.getModifiers()))
					.as("SECURE_RANDOM must be static to avoid per-call entropy drain").isTrue();
		}

		/**
		 * Uses reflection to assert {@code SECURE_RANDOM} is typed as
		 * {@link SecureRandom} (a CSPRNG) rather than the predictable
		 * {@link java.util.Random}, which would make IVs guessable.
		 *
		 * @throws Exception if the reflective field lookup fails
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("SECURE_RANDOM is of type java.security.SecureRandom")
		void secureRandomType() throws Exception {
			Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(f.getType()).isEqualTo(SecureRandom.class);
		}
	}
}
