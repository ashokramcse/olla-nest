package com.ollanest.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF-focused unit tests for {@link UrlValidator}.
 *
 * <p>
 * Guards BUG-020: IPv6 loopback {@code [::1]}, IPv6 unique-local
 * {@code fc00::/7}, and wildcard {@code 0.0.0.0} must all be rejected,
 * alongside the IPv4 private ranges.
 *
 * @author Ashok Ram
 * @since v2026.2.2
 * @version v2026.2.2
 */
@DisplayName("UrlValidator — SSRF safety")
class UrlValidatorTest {

	@ParameterizedTest(name = "unsafe: {0}")
	@ValueSource(strings = { "http://127.0.0.1/x", "http://localhost/x", "http://10.0.0.1/x", "http://172.16.0.1/x",
			"http://192.168.1.1/x", "http://169.254.169.254/latest/meta-data/", // cloud metadata
			"http://0.0.0.0/x", // wildcard → localhost
			"http://[::1]/x", // IPv6 loopback (BUG-020)
			"http://[fc00::1]/x", // IPv6 ULA (BUG-020)
			"http://[fd00::1]/x", "ftp://example.com/x", // non-http scheme
			"not-a-url", "" })
	/**
	 * Every SSRF-relevant URL — private/loopback/link-local/metadata IPv4 and IPv6
	 * targets (including the BUG-020 IPv6 cases), non-http schemes, and malformed
	 * input — must be rejected by the central SSRF guard.
	 *
	 * @param url the candidate URL supplied by the parameterised source
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@DisplayName("private / loopback / metadata / non-http URLs are rejected")
	void unsafeUrlsRejected(String url) {
		assertThat(UrlValidator.isSafeUrl(url)).as("'%s' must be unsafe", url).isFalse();
	}

	@ParameterizedTest(name = "safe: {0}")
	@ValueSource(strings = { "https://example.com/ok", "http://93.184.216.34/recv" // public IP literal
	})
	/**
	 * Genuinely public http(s) URLs (a hostname and a public IP literal) must be
	 * allowed, so the SSRF guard does not over-block legitimate webhook/connector
	 * destinations.
	 *
	 * @param url the candidate URL supplied by the parameterised source
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@DisplayName("public http(s) URLs are allowed")
	void safeUrlsAllowed(String url) {
		assertThat(UrlValidator.isSafeUrl(url)).as("'%s' must be safe", url).isTrue();
	}

	/**
	 * A null URL must be rejected (treated as unsafe) rather than throwing.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Test
	@DisplayName("null is rejected")
	void nullRejected() {
		assertThat(UrlValidator.isSafeUrl(null)).isFalse();
	}
}
