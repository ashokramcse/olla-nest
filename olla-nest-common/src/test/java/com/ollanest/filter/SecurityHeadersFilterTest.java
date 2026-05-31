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
		verify(response).setHeader("X-Content-Type-Options", "nosniff");
	}

	@Test
	@DisplayName("sets X-Frame-Options: DENY on every response")
	void setsXFrameOptions() throws Exception {
		filter.doFilterInternal(request, response, chain);
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
		verify(response).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
	}

	@Test
	@DisplayName("sets Content-Security-Policy on every response")
	void setsContentSecurityPolicy() throws Exception {
		filter.doFilterInternal(request, response, chain);
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
		when(request.isSecure()).thenReturn(false);
		filter.doFilterInternal(request, response, chain);
		verify(response, never()).setHeader(eq("Strict-Transport-Security"), anyString());
	}

	@Test
	@DisplayName("HSTS header added on HTTPS (isSecure=true)")
	void hstsOnHttps() throws Exception {
		when(request.isSecure()).thenReturn(true);
		filter.doFilterInternal(request, response, chain);
		verify(response).setHeader(eq("Strict-Transport-Security"), argThat(v ->
				v.contains("max-age=31536000")
				&& v.contains("includeSubDomains")
		));
	}

	@Test
	@DisplayName("filter chain always continues — response is never committed early")
	void chainAlwaysContinues() throws Exception {
		filter.doFilterInternal(request, response, chain);
		verify(chain).doFilter(request, response);
	}
}
