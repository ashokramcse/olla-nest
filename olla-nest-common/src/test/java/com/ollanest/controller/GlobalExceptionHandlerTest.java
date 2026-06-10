package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>
 * Verifies that each exception type is translated to the correct HTTP status
 * and {@code {ok: false, error: "..."}} JSON envelope without leaking any stack
 * trace detail.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
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
		// Use reflection to instantiate — constructor is package-private in some
		// versions
		NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/static/missing.js");
		ResponseEntity<Map<String, Object>> r = handler.handleNotFound(ex);
		// Static resource 404 must produce the same envelope as route 404
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(r.getBody()).containsEntry("ok", false);
	}

	@Test
	@DisplayName("HttpRequestMethodNotSupportedException → 405 with supported methods in error")
	void handlesMethodNotAllowed() {
		HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE",
				Set.of("GET", "POST"));
		ResponseEntity<Map<String, Object>> r = handler.handleMethodNotAllowed(ex);
		// 405 must include the unsupported method name so the client understands the
		// rejection
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).contains("DELETE");
	}

	@Test
	@DisplayName("HttpMessageNotReadableException → 400 with ok=false")
	void handlesBadJson() {
		HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
		ResponseEntity<Map<String, Object>> r = handler.handleBadJson(ex);
		// Malformed request body must return 400 with a descriptive but safe error
		// message
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("malformed");
	}

	@Test
	@DisplayName("HttpMediaTypeNotSupportedException → 415 with ok=false")
	void handlesUnsupportedMediaType() {
		HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
				List.of(MediaType.APPLICATION_JSON));
		ResponseEntity<Map<String, Object>> r = handler.handleUnsupportedMediaType(ex);
		// 415 must identify the unsupported content-type in the error message
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("text/plain");
	}

	@Test
	@DisplayName("MissingServletRequestParameterException → 400 (regression: was 500)")
	void handlesMissingRequestParam() {
		// Regression for BUG-001: an omitted required @RequestParam (e.g.
		// GET /api/memory/search with no ?q=) previously fell through to the generic
		// catch-all and surfaced as a misleading 500. It must be a 400 Bad Request.
		MissingServletRequestParameterException ex = new MissingServletRequestParameterException("q", "String");
		ResponseEntity<Map<String, Object>> r = handler.handleMissingParam(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		// The error must name the missing parameter so the client can correct the call
		assertThat(r.getBody().get("error").toString()).contains("q");
	}

	/**
	 * A file-upload endpoint invoked with no file part must surface as a 400 (caller
	 * error), not the misleading 500 that the framework's
	 * {@link org.springframework.web.multipart.support.MissingServletRequestPartException}
	 * would otherwise produce via the generic catch-all (BUG-038 / BUG-025 class).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("MissingServletRequestPartException → 400 (regression: upload with no file was 500) — BUG-038")
	void handlesMissingMultipartPart() {
		MissingServletRequestPartException ex = new MissingServletRequestPartException("file");
		ResponseEntity<Map<String, Object>> r = handler.handleMissingPart(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(r.getBody()).containsEntry("ok", false);
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("file upload");
	}

	@Test
	@DisplayName("ProviderUnavailableException → 503 (provider not configured is environmental, not a 500) — BUG-030")
	void handlesProviderUnavailable() {
		ProviderUnavailableException ex = new ProviderUnavailableException("OpenAI API key not configured for TTS");
		ResponseEntity<Map<String, Object>> r = handler.handleProviderUnavailable(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(r.getBody()).containsEntry("ok", false);
		// The actionable message is preserved (it names the missing config, not
		// internal detail).
		assertThat(r.getBody().get("error").toString()).containsIgnoringCase("not configured");
	}

	@Test
	@DisplayName("generic RuntimeException → 500 with generic message (no stack trace leaked)")
	void handlesGenericException() {
		RuntimeException ex = new RuntimeException("DB connection pool exhausted");
		ResponseEntity<Map<String, Object>> r = handler.handleGeneric(ex);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(r.getBody()).containsEntry("ok", false);
		// SECURITY: internal detail must NOT be exposed — generic message only
		assertThat(r.getBody().get("error").toString()).doesNotContain("DB connection pool", "RuntimeException")
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
