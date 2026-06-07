package com.ollanest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SecurityHeadersFilter}.
 *
 * <p>Verifies all required security headers are emitted exactly once per
 * response, that HSTS is only added on HTTPS, and that the filter chain
 * always continues.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityHeadersFilter — unit tests")
class SecurityHeadersFilterTest {

	@Mock HttpServletRequest  request;
	@Mock HttpServletResponse response;
	@Mock FilterChain         chain;

	@InjectMocks SecurityHeadersFilter filter;

	@Test
	@DisplayName("sets X-Content-Type-Options: nosniff on every response")
	void setsXContentTypeOptions() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// Prevents MIME-type sniffing that could turn benign content into executable
		verify(response).setHeader("X-Content-Type-Options", "nosniff");
	}

	@Test
	@DisplayName("sets X-Frame-Options: DENY on every response")
	void setsXFrameOptions() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// DENY prevents clickjacking via iframes on any domain
		verify(response).setHeader("X-Frame-Options", "DENY");
	}

	@Test
	@DisplayName("X-XSS-Protection header is NOT set (MED-6: deprecated and harmful in modern browsers)")
	void xXssProtectionNotSet() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// MED-6: X-XSS-Protection was removed because it is deprecated and the
		// Chromium XSS Auditor was removed; setting it can cause unintended page-blocking.
		// CSP is the correct XSS mitigation.
		verify(response, never()).setHeader(eq("X-XSS-Protection"), anyString());
	}

	@Test
	@DisplayName("sets Referrer-Policy on every response")
	void setsReferrerPolicy() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// Restricts referrer information sent to third-party sites
		verify(response).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
	}

	@Test
	@DisplayName("sets Content-Security-Policy on every response")
	void setsContentSecurityPolicy() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// CSP must restrict default source, forbid framing, and restrict base URI
		verify(response).setHeader(eq("Content-Security-Policy"), argThat(v ->
				v.contains("default-src 'self'")
				&& v.contains("frame-ancestors 'none'")
				&& v.contains("base-uri 'self'")
		));
	}

	@Test
	@DisplayName("sets Permissions-Policy header on every response")
	void setsPermissionsPolicy() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// CRIT-5: microphone=(self) allows voice recording from same origin;
		// camera, geolocation, payment remain fully disabled.
		verify(response).setHeader(eq("Permissions-Policy"), argThat(v ->
				v.contains("camera=()")
				&& v.contains("microphone=(self)")
				&& v.contains("geolocation=()")
				&& v.contains("payment=()")
		));
	}

	@Test
	@DisplayName("HSTS header NOT added on plain HTTP (isSecure=false)")
	void noHstsOnHttp() throws Exception {
		// Stub: plain HTTP connection — HSTS must NOT be set (would break HTTP access)
		when(request.isSecure()).thenReturn(false);
		filter.doFilterInternal(request, response, chain);
		// SECURITY: HSTS on plain HTTP would poison the browser and block future HTTP access
		verify(response, never()).setHeader(eq("Strict-Transport-Security"), anyString());
	}

	@Test
	@DisplayName("HSTS header added on HTTPS (isSecure=true)")
	void hstsOnHttps() throws Exception {
		// Stub: HTTPS connection — HSTS must be set
		when(request.isSecure()).thenReturn(true);
		filter.doFilterInternal(request, response, chain);
		// HSTS must include a long max-age and cover subdomains
		verify(response).setHeader(eq("Strict-Transport-Security"), argThat(v ->
				v.contains("max-age=31536000")
				&& v.contains("includeSubDomains")
		));
	}

	@Test
	@DisplayName("filter chain always continues — response is never committed early")
	void chainAlwaysContinues() throws Exception {
		filter.doFilterInternal(request, response, chain);
		// Security headers are added but the request continues to the next filter/handler
		verify(chain).doFilter(request, response);
	}
}
