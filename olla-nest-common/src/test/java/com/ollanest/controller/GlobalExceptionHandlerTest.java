package com.ollanest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Verifies that each exception type is translated to the correct
 * HTTP status and {@code {ok: false, error: "..."}} JSON envelope without
 * leaking any stack trace detail.
 */
@DisplayName("GlobalExceptionHandler — unit tests")
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("NoHandlerFoundException → 404 with ok=false")
	void handlesNoHandlerFound() {
		NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/missing", null);
		ResponseEntity<Map<String, Object>> r = handler.handleNotFound(ex);
		// 404 must be returned with ok=false and a generic "Not found" message
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error")).isEqualTo("Not found");
	}

	@Test
	@DisplayName("NoResourceFoundException → 404 with ok=false")
	void handlesNoResourceFound() throws Exception {
		// Use reflection to instantiate — constructor is package-private in some versions
		NoResourceFoundException ex = new NoResourceFoundException(
				org.springframework.http.HttpMethod.GET, "/static/missing.js");
		ResponseEntity<Map<String, Object>> r = handler.handleNotFound(ex);
		// Static resource 404 must produce the same envelope as route 404
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(r.getBody()).containsEntry("ok", false);
	}

	@Test
	@DisplayName("HttpRequestMethodNotSupportedException → 405 with supported methods in error")
	void handlesMethodNotAllowed() {
		HttpRequestMethodNotSupportedException ex =
				new HttpRequestMethodNotSupportedException("DELETE",
						Set.of("GET", "POST"));
		ResponseEntity<Map<String, Object>> r = handler.handleMethodNotAllowed(ex);
		// 405 must include the unsupported method name so the client understands the rejection
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).contains("DELETE");
	}

	@Test
	@DisplayName("HttpMessageNotReadableException → 400 with ok=false")
	void handlesBadJson() {
		HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
		ResponseEntity<Map<String, Object>> r = handler.handleBadJson(ex);
		// Malformed request body must return 400 with a descriptive but safe error message
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("malformed");
	}

	@Test
	@DisplayName("HttpMediaTypeNotSupportedException → 415 with ok=false")
	void handlesUnsupportedMediaType() {
		HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON));
		ResponseEntity<Map<String, Object>> r = handler.handleUnsupportedMediaType(ex);
		// 415 must identify the unsupported content-type in the error message
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("text/plain");
	}

	@Test
	@DisplayName("generic RuntimeException → 500 with generic message (no stack trace leaked)")
	void handlesGenericException() {
		RuntimeException ex = new RuntimeException("DB connection pool exhausted");
		ResponseEntity<Map<String, Object>> r = handler.handleGeneric(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(r.getBody()).containsEntry("ok", false);
		// SECURITY: internal detail must NOT be exposed — generic message only
		assertThat(r.getBody().get("error").toString())
				.doesNotContain("DB connection pool", "RuntimeException")
				.isEqualTo("Internal server error");
	}

	@Test
	@DisplayName("NullPointerException → 500 with generic message")
	void handlesNullPointerException() {
		NullPointerException ex = new NullPointerException("null ref in service layer");
		ResponseEntity<Map<String, Object>> r = handler.handleGeneric(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		// SECURITY: NPE message containing internal details must not appear in response
		assertThat(r.getBody().get("error").toString()).doesNotContain("null ref");
	}
}
