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
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@DisplayName("CryptoService — unit tests")
class CryptoServiceTest {

	private final CryptoService crypto = new CryptoService("test-encryption-key-for-junit");

	// ── Round-trip ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("encrypt / decrypt round-trip")
	class RoundTrip {

		@Test
		@DisplayName("encrypts and decrypts a simple string")
		void simpleString() {
			String plaintext = "super-secret-api-key-12345";
			String encrypted = crypto.encryptKey(plaintext);
			assertThat(crypto.decryptKey(encrypted)).isEqualTo(plaintext);
		}

		@Test
		@DisplayName("round-trips an empty string")
		void emptyString() {
			String encrypted = crypto.encryptKey("");
			assertThat(crypto.decryptKey(encrypted)).isEqualTo("");
		}

		@Test
		@DisplayName("round-trips a Unicode string (multibyte UTF-8)")
		void unicodeString() {
			String plaintext = "日本語テスト🔑";
			assertThat(crypto.decryptKey(crypto.encryptKey(plaintext))).isEqualTo(plaintext);
		}

		@Test
		@DisplayName("round-trips a 10 KB payload")
		void largePayload() {
			String large = "x".repeat(10_240);
			assertThat(crypto.decryptKey(crypto.encryptKey(large))).isEqualTo(large);
		}

		@Test
		@DisplayName("encrypted output contains exactly two colons (iv:tag:ciphertext)")
		void outputHasTwoColons() {
			String enc = crypto.encryptKey("hello");
			long colons = enc.chars().filter(c -> c == ':').count();
			assertThat(colons).isEqualTo(2);
		}

		@Test
		@DisplayName("IV segment is exactly 24 hex chars (12 bytes)")
		void ivIs24HexChars() {
			String enc = crypto.encryptKey("hello");
			String iv = enc.split(":")[0];
			assertThat(iv).hasSize(24).matches("[0-9a-f]+");
		}

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

		@Test
		@DisplayName("null input returns empty string (no exception)")
		void nullInput() {
			assertThat(crypto.decryptKey(null)).isEqualTo("");
		}

		@Test
		@DisplayName("blank input returns empty string (no exception)")
		void blankInput() {
			assertThat(crypto.decryptKey("   ")).isEqualTo("");
		}

		@Test
		@DisplayName("malformed input (not enough colons) returns empty string")
		void malformedInput() {
			assertThat(crypto.decryptKey("notvalid")).isEqualTo("");
		}

		@Test
		@DisplayName("truncated ciphertext returns empty string (tag check fails)")
		void truncatedCiphertext() {
			String enc = crypto.encryptKey("secret");
			// Corrupt the ciphertext segment
			String[] parts = enc.split(":");
			String corrupted = parts[0] + ":" + parts[1] + ":deadbeef";
			assertThat(crypto.decryptKey(corrupted)).isEqualTo("");
		}

		@Test
		@DisplayName("wrong key returns empty string (GCM authentication fails)")
		void wrongKey() {
			String encrypted = crypto.encryptKey("my secret");
			CryptoService wrongKeyService = new CryptoService("completely-different-key");
			assertThat(wrongKeyService.decryptKey(encrypted)).isEqualTo("");
		}

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

		@Test
		@DisplayName("SECURE_RANDOM is a static field — no per-call instantiation")
		void secureRandomIsStatic() throws Exception {
			Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(Modifier.isStatic(f.getModifiers()))
					.as("SECURE_RANDOM must be static to avoid per-call entropy drain").isTrue();
		}

		@Test
		@DisplayName("SECURE_RANDOM is of type java.security.SecureRandom")
		void secureRandomType() throws Exception {
			Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
			assertThat(f.getType()).isEqualTo(SecureRandom.class);
		}
	}
}
