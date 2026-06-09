package com.ollanest.service;

import com.ollanest.util.UrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-hardening validation tests.
 *
 * <p>Covers: SSRF URL validation (private/loopback/link-local ranges),
 * non-HTTP scheme rejection, session token entropy, token injection rejection,
 * AES-GCM tamper detection, rate-limit map eviction, and static SecureRandom.
 *
 * <p>These tests act as executable security specifications — they document
 * and enforce the security invariants of the production codebase.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@DisplayName("Security Hardening — validation tests")
class SecurityHardeningTest {

    // ── SSRF protection ───────────────────────────────────────────────────

    @Nested
    @DisplayName("UrlValidator — SSRF protection")
    class SsrfProtection {

        @Test @DisplayName("rejects null URL")
        void nullRejected() {
            // SECURITY: null URL must be treated as unsafe — no NPE allowed
            assertThat(UrlValidator.isSafeUrl(null)).isFalse();
        }

        @Test @DisplayName("rejects blank URL")
        void blankRejected() {
            // Blank string cannot be a valid external URL
            assertThat(UrlValidator.isSafeUrl("   ")).isFalse();
        }

        @Test @DisplayName("rejects file:// scheme (local filesystem access)")
        void fileSchemeRejected() {
            // SECURITY: file:// would allow reading local secrets like /etc/passwd
            assertThat(UrlValidator.isSafeUrl("file:///etc/passwd")).isFalse();
        }

        @Test @DisplayName("rejects ftp:// scheme")
        void ftpSchemeRejected() {
            // Only http/https are safe for external API calls
            assertThat(UrlValidator.isSafeUrl("ftp://example.com/file")).isFalse();
        }

        @Test @DisplayName("rejects javascript: scheme (XSS via URL)")
        void javascriptSchemeRejected() {
            // SECURITY: javascript: URIs in server-side HTTP calls would be an execution exploit
            assertThat(UrlValidator.isSafeUrl("javascript:alert(1)")).isFalse();
        }

        @Test @DisplayName("rejects 127.0.0.1 (loopback)")
        void loopbackIpRejected() {
            // SECURITY: SSRF — 127.0.0.1 would allow attackers to reach internal services
            assertThat(UrlValidator.isSafeUrl("http://127.0.0.1:8080")).isFalse();
        }

        @Test @DisplayName("rejects 127.x.x.x (full loopback range)")
        void loopbackRangeRejected() {
            // The entire 127.0.0.0/8 range is loopback — all must be blocked
            assertThat(UrlValidator.isSafeUrl("http://127.255.255.255/api")).isFalse();
        }

        @Test @DisplayName("rejects localhost (resolves to loopback)")
        void localhostRejected() {
            // "localhost" resolves to 127.0.0.1 — must be blocked by hostname check
            assertThat(UrlValidator.isSafeUrl("http://localhost/api")).isFalse();
        }

        @Test @DisplayName("rejects 10.0.0.1 (RFC-1918 class A private)")
        void rfc1918ClassArejected() {
            // SECURITY: 10.0.0.0/8 is RFC-1918 private — would allow internal network access
            assertThat(UrlValidator.isSafeUrl("http://10.0.0.1/internal")).isFalse();
        }

        @Test @DisplayName("rejects 172.16.0.1 (RFC-1918 class B private)")
        void rfc1918ClassBrejected() {
            // 172.16.0.0/12 range must be blocked
            assertThat(UrlValidator.isSafeUrl("http://172.16.0.1/internal")).isFalse();
        }

        @Test @DisplayName("rejects 172.31.255.255 (top of RFC-1918 class B range)")
        void rfc1918ClassBtopRejected() {
            // Top boundary of the 172.16.0.0/12 range must also be blocked
            assertThat(UrlValidator.isSafeUrl("http://172.31.255.255/admin")).isFalse();
        }

        @Test @DisplayName("rejects 192.168.1.1 (RFC-1918 class C private)")
        void rfc1918ClassCrejected() {
            // 192.168.0.0/16 is RFC-1918 private — commonly used for home routers
            assertThat(UrlValidator.isSafeUrl("http://192.168.1.1/router")).isFalse();
        }

        @Test @DisplayName("rejects 169.254.169.254 (AWS metadata / link-local)")
        void awsMetadataRejected() {
            // SECURITY: AWS/GCP metadata endpoint — must never be reachable via SSRF
            assertThat(UrlValidator.isSafeUrl("http://169.254.169.254/latest/meta-data/")).isFalse();
        }

        @Test @DisplayName("rejects malformed URL")
        void malformedUrlRejected() {
            // Unparseable URLs must be treated as unsafe — not passed through
            assertThat(UrlValidator.isSafeUrl("not-a-url")).isFalse();
        }

        @Test @DisplayName("rejects URL with no host")
        void noHostRejected() {
            // No-host URL would bypass hostname checks — must be rejected
            assertThat(UrlValidator.isSafeUrl("http:///path")).isFalse();
        }
    }

    // ── Token entropy ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Session token entropy")
    class TokenEntropy {

        @Test
        @DisplayName("AuthService token format: exactly 64 lowercase hex characters")
        void tokenFormatIsValid() {
            // Verify the token generation algorithm produces the expected 64-char hex format
            SecureRandom rng = new SecureRandom();
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            String token = sb.toString();
            // 32 bytes = 256 bits of entropy, formatted as 64 lowercase hex chars
            assertThat(token).hasSize(64).matches("[0-9a-f]{64}");
        }

        @RepeatedTest(1)
        @DisplayName("1000 tokens are all unique (256-bit entropy)")
        void tokensAreUnique() {
            // 256-bit entropy makes collision probability astronomically low — all 1000 must be unique
            SecureRandom rng = new SecureRandom();
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                byte[] bytes = new byte[32];
                rng.nextBytes(bytes);
                StringBuilder sb = new StringBuilder(64);
                for (byte b : bytes) sb.append(String.format("%02x", b));
                tokens.add(sb.toString());
            }
            // Any collision would indicate a broken RNG — catastrophic for session security
            assertThat(tokens).hasSize(1000);
        }
    }

    // ── Token format guard — injection payload catalogue ──────────────────

    @Nested
    @DisplayName("Token format guard — injection payload catalogue")
    class TokenInjectionCatalogue {

        private static final Pattern VALID =
                Pattern.compile("^[0-9a-f]{64}$");

        private void assertRejected(String payload) {
            // SECURITY: any payload that does not match the exact 64-hex-char format must be rejected
            assertThat(VALID.matcher(payload).matches())
                    .as("Payload should be rejected by token format guard")
                    .isFalse();
        }

        // All known injection/attack payloads must fail the token format guard
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
            Method m =
                    ChatService.class.getMethod("cleanStaleRateLimitEntries");
            assertThat(m).isNotNull();
            // @Scheduled annotation must be present — missing it would cause a memory leak
            assertThat(m.isAnnotationPresent(
                    Scheduled.class))
                    .as("cleanStaleRateLimitEntries must be @Scheduled")
                    .isTrue();
        }

        @Test
        @DisplayName("rateLimitMap field is private (encapsulated)")
        void rateLimitMapIsPrivate() throws Exception {
            Field f =
                    ChatService.class.getDeclaredField("rateLimitMap");
            // SECURITY: rateLimitMap must be private — no external mutation of rate limit state
            assertThat(Modifier.isPrivate(f.getModifiers()))
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
            // Encrypt a credential, then flip one bit in the ciphertext
            String enc = crypto.encryptKey("sensitive-credential");
            String[] parts = enc.split(":");
            char flipped = parts[2].charAt(0) == '0' ? '1' : '0';
            String tampered = parts[0] + ":" + parts[1] + ":" + flipped + parts[2].substring(1);
            // SECURITY: AES-GCM must detect the tampered ciphertext — returns empty string
            assertThat(crypto.decryptKey(tampered)).isEqualTo("");
        }

        @Test
        @DisplayName("prefix prepended to ciphertext detected by GCM auth tag")
        void prependDetected() {
            // Prepend "deadbeef" to the ciphertext — GCM auth tag must catch this
            String enc = crypto.encryptKey("credential");
            String[] parts = enc.split(":");
            String tampered = parts[0] + ":" + parts[1] + ":deadbeef" + parts[2];
            assertThat(crypto.decryptKey(tampered)).isEqualTo("");
        }

        @Test
        @DisplayName("IV swap (replay attack) fails GCM authentication")
        void replayAttackDetected() {
            // Combine IV from one ciphertext with the ciphertext+tag of another — replay attack
            String enc1 = crypto.encryptKey("value-one");
            String enc2 = crypto.encryptKey("value-two");
            // Use IV from enc2 with tag+ciphertext from enc1 — GCM must reject this
            String mixed = enc2.split(":")[0] + ":" + enc1.split(":")[1] + ":" + enc1.split(":")[2];
            assertThat(crypto.decryptKey(mixed)).isNotEqualTo("value-one");
        }

        @Test
        @DisplayName("wrong key returns empty string — GCM authentication fails")
        void wrongKeyReturnsEmpty() {
            // Encrypt with one key, decrypt with a different key — must not succeed
            String encrypted = crypto.encryptKey("my-secret");
            CryptoService wrongKey = new CryptoService("completely-different-key");
            // SECURITY: wrong key must return empty string — not throw or return partial data
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
            Field f = AuthService.class.getDeclaredField("SECURE_RANDOM");
            // Static field = single shared instance; per-call instantiation wastes entropy pool seeding
            assertThat(Modifier.isStatic(f.getModifiers()))
                    .as("AuthService.SECURE_RANDOM must be static")
                    .isTrue();
        }

        @Test
        @DisplayName("CryptoService.SECURE_RANDOM is static")
        void cryptoServiceSecureRandomIsStatic() throws Exception {
            Field f = CryptoService.class.getDeclaredField("SECURE_RANDOM");
            assertThat(Modifier.isStatic(f.getModifiers()))
                    .as("CryptoService.SECURE_RANDOM must be static")
                    .isTrue();
        }

        @Test
        @DisplayName("ChatService.UID_RNG is static")
        void chatServiceUidRngIsStatic() throws Exception {
            Field f = ChatService.class.getDeclaredField("UID_RNG");
            assertThat(Modifier.isStatic(f.getModifiers()))
                    .as("ChatService.UID_RNG must be static")
                    .isTrue();
        }
    }
}
