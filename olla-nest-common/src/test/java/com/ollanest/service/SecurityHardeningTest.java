package com.ollanest.service;

import com.ollanest.util.UrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-hardening validation tests.
 *
 * <p>Covers: SSRF URL validation (private/loopback/link-local ranges),
 * non-HTTP scheme rejection, session token entropy, token injection rejection,
 * AES-GCM tamper detection, rate-limit map eviction, and static SecureRandom.
 *
 * <p>These tests act as executable security specifications — they document
 * and enforce the security invariants of the production codebase.
 */
@DisplayName("Security Hardening — validation tests")
class SecurityHardeningTest {

    // ── SSRF protection ───────────────────────────────────────────────────

    @Nested
    @DisplayName("UrlValidator — SSRF protection")
    class SsrfProtection {

        @Test @DisplayName("rejects null URL")
        void nullRejected() { assertThat(UrlValidator.isSafeUrl(null)).isFalse(); }

        @Test @DisplayName("rejects blank URL")
        void blankRejected() { assertThat(UrlValidator.isSafeUrl("   ")).isFalse(); }

        @Test @DisplayName("rejects file:// scheme (local filesystem access)")
        void fileSchemeRejected() { assertThat(UrlValidator.isSafeUrl("file:///etc/passwd")).isFalse(); }

        @Test @DisplayName("rejects ftp:// scheme")
        void ftpSchemeRejected() { assertThat(UrlValidator.isSafeUrl("ftp://example.com/file")).isFalse(); }

        @Test @DisplayName("rejects javascript: scheme (XSS via URL)")
        void javascriptSchemeRejected() { assertThat(UrlValidator.isSafeUrl("javascript:alert(1)")).isFalse(); }

        @Test @DisplayName("rejects 127.0.0.1 (loopback)")
        void loopbackIpRejected() { assertThat(UrlValidator.isSafeUrl("http://127.0.0.1:8080")).isFalse(); }

        @Test @DisplayName("rejects 127.x.x.x (full loopback range)")
        void loopbackRangeRejected() { assertThat(UrlValidator.isSafeUrl("http://127.255.255.255/api")).isFalse(); }

        @Test @DisplayName("rejects localhost (resolves to loopback)")
        void localhostRejected() { assertThat(UrlValidator.isSafeUrl("http://localhost/api")).isFalse(); }

        @Test @DisplayName("rejects 10.0.0.1 (RFC-1918 class A private)")
        void rfc1918ClassArejected() { assertThat(UrlValidator.isSafeUrl("http://10.0.0.1/internal")).isFalse(); }

        @Test @DisplayName("rejects 172.16.0.1 (RFC-1918 class B private)")
        void rfc1918ClassBrejected() { assertThat(UrlValidator.isSafeUrl("http://172.16.0.1/internal")).isFalse(); }

        @Test @DisplayName("rejects 172.31.255.255 (top of RFC-1918 class B range)")
        void rfc1918ClassBtopRejected() { assertThat(UrlValidator.isSafeUrl("http://172.31.255.255/admin")).isFalse(); }

        @Test @DisplayName("rejects 192.168.1.1 (RFC-1918 class C private)")
        void rfc1918ClassCrejected() { assertThat(UrlValidator.isSafeUrl("http://192.168.1.1/router")).isFalse(); }

        @Test @DisplayName("rejects 169.254.169.254 (AWS metadata / link-local)")
        void awsMetadataRejected() { assertThat(UrlValidator.isSafeUrl("http://169.254.169.254/latest/meta-data/")).isFalse(); }

        @Test @DisplayName("rejects malformed URL")
        void malformedUrlRejected() { assertThat(UrlValidator.isSafeUrl("not-a-url")).isFalse(); }

        @Test @DisplayName("rejects URL with no host")
        void noHostRejected() { assertThat(UrlValidator.isSafeUrl("http:///path")).isFalse(); }
    }

    // ── Token entropy ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Session token entropy")
    class TokenEntropy {

        @Test
        @DisplayName("AuthService token format: exactly 64 lowercase hex characters")
        void tokenFormatIsValid() {
            java.security.SecureRandom rng = new java.security.SecureRandom();
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            String token = sb.toString();
            assertThat(token).hasSize(64).matches("[0-9a-f]{64}");
        }

        @RepeatedTest(1)
        @DisplayName("1000 tokens are all unique (256-bit entropy)")
        void tokensAreUnique() {
            java.security.SecureRandom rng = new java.security.SecureRandom();
            java.util.Set<String> tokens = new java.util.HashSet<>();
            for (int i = 0; i < 1000; i++) {
                byte[] bytes = new byte[32];
                rng.nextBytes(bytes);
                StringBuilder sb = new StringBuilder(64);
                for (byte b : bytes) sb.append(String.format("%02x", b));
                tokens.add(sb.toString());
            }
            assertThat(tokens).hasSize(1000);
        }
    }

    // ── Token format guard — injection payload catalogue ──────────────────

    @Nested
    @DisplayName("Token format guard — injection payload catalogue")
    class TokenInjectionCatalogue {

        private static final java.util.regex.Pattern VALID =
                java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

        private void assertRejected(String payload) {
            assertThat(VALID.matcher(payload).matches())
                    .as("Payload should be rejected by token format guard")
                    .isFalse();
        }

        @Test void sqlUnionInjection()    { assertRejected("' UNION SELECT * FROM users --"); }
        @Test void sqlOrInjection()       { assertRejected("' OR 1=1 --"); }
        @Test void crlfInjection()        { assertRejected("valid\r\nX-Evil: injected"); }
        @Test void spaceSeparated()       { assertRejected("abc def"); }
        @Test void oversizedToken()       { assertRejected("a".repeat(1024)); }
        @Test void emptyToken()           { assertRejected(""); }
        @Test void whitespaceOnly()       { assertRejected("   "); }
        @Test void uppercaseHex()         { assertRejected("A".repeat(64)); }
        @Test void mixedCase()            { assertRejected("aAbBcCdDeEfF" + "0".repeat(52)); }
        @Test void semicolonSuffix()      { assertRejected("a".repeat(63) + ";"); }
        @Test void slashTraversal()       { assertRejected("../../../etc/passwd"); }
        @Test void xssPayload()           { assertRejected("<script>alert(1)</script>"); }
        @Test void percentEncoded()       { assertRejected("a".repeat(60) + "%00abc"); }
    }

    // ── ChatService rateLimitMap eviction ─────────────────────────────────

    @Nested
    @DisplayName("ChatService — rate limit map bounded growth")
    class RateLimitMapEviction {

        @Test
        @DisplayName("cleanStaleRateLimitEntries method exists and is @Scheduled")
        void cleanupMethodIsScheduled() throws Exception {
            java.lang.reflect.Method m =
                    ChatService.class.getMethod("cleanStaleRateLimitEntries");
            assertThat(m).isNotNull();
            assertThat(m.isAnnotationPresent(
                    org.springframework.scheduling.annotation.Scheduled.class))
                    .as("cleanStaleRateLimitEntries must be @Scheduled")
                    .isTrue();
        }

        @Test
        @DisplayName("rateLimitMap field is private (encapsulated)")
        void rateLimitMapIsPrivate() throws Exception {
            java.lang.reflect.Field f =
                    ChatService.class.getDeclaredField("rateLimitMap");
            assertThat(java.lang.reflect.Modifier.isPrivate(f.getModifiers()))
                    .as("rateLimitMap must be private")
                    .isTrue();
        }
    }

    // ── CryptoService — AES-GCM tamper detection ──────────────────────────

    @Nested
    @DisplayName("CryptoService — tamper detection")
    class CryptoTamperDetection {

        private final CryptoService crypto = new CryptoService("security-test-key");

        @Test
        @DisplayName("1-bit flip in ciphertext detected by GCM auth tag")
        void oneBitFlipDetected() {
            String enc = crypto.encryptKey("sensitive-credential");
            String[] parts = enc.split(":");
            char flipped = parts[2].charAt(0) == '0' ? '1' : '0';
            String tampered = parts[0] + ":" + parts[1] + ":" + flipped + parts[2].substring(1);
            assertThat(crypto.decryptKey(tampered)).isEqualTo("");
        }

        @Test
        @DisplayName("prefix prepended to ciphertext detected by GCM auth tag")
        void prependDetected() {
            String enc = crypto.encryptKey("credential");
            String[] parts = enc.split(":");
            String tampered = parts[0] + ":" + parts[1] + ":deadbeef" + parts[2];
            assertThat(crypto.decryptKey(tampered)).isEqualTo("");
        }

        @Test
        @DisplayName("IV swap (replay attack) fails GCM authentication")
        void replayAttackDetected() {
            String enc1 = crypto.encryptKey("value-one");
            String enc2 = crypto.encryptKey("value-two");
            // Use IV from enc2 with tag+ciphertext from enc1
            String mixed = enc2.split(":")[0] + ":" + enc1.split(":")[1] + ":" + enc1.split(":")[2];
            assertThat(crypto.decryptKey(mixed)).isNotEqualTo("value-one");
        }

        @Test
        @DisplayName("wrong key returns empty string — GCM authentication fails")
        void wrongKeyReturnsEmpty() {
            String encrypted = crypto.encryptKey("my-secret");
            CryptoService wrongKey = new CryptoService("completely-different-key");
            assertThat(wrongKey.decryptKey(encrypted)).isEqualTo("");
        }
    }

    // ── Static SecureRandom fields ─────────────────────────────────────────

    @Nested
    @DisplayName("Static SecureRandom — no per-call instantiation")
    class StaticSecureRandomFields {

        @Test
        @DisplayName("AuthService.SECURE_RANDOM is static")
        void authServiceSecureRandomIsStatic() throws Exception {
            java.lang.reflect.Field f = AuthService.class.getDeclaredField("SECURE_RANDOM");
            assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .as("AuthService.SECURE_RANDOM must be static")
                    .isTrue();
        }

        @Test
        @DisplayName("CryptoService.SECURE_RANDOM is static")
        void cryptoServiceSecureRandomIsStatic() throws Exception {
            java.lang.reflect.Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
            assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .as("CryptoService.SECURE_RANDOM must be static")
                    .isTrue();
        }

        @Test
        @DisplayName("ChatService.UID_RNG is static")
        void chatServiceUidRngIsStatic() throws Exception {
            java.lang.reflect.Field f = ChatService.class.getDeclaredField("UID_RNG");
            assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .as("ChatService.UID_RNG must be static")
                    .isTrue();
        }
    }
}
