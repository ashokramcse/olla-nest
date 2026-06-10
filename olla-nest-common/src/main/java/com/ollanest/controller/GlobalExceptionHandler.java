package com.ollanest.controller;

import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler that converts unhandled exceptions into a consistent
 * {@code {ok: false, error: "message"}} JSON envelope.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Without this handler, Spring Boot returns its own {@code /error} page JSON
 * for unhandled exceptions, which has a different shape ({@code timestamp},
 * {@code status}, {@code error}, {@code path}) than the rest of the Olla Nest
 * API. This causes frontend consumers to fail on otherwise-handled error paths
 * and produces unintended information disclosure via the default error body.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Only maps exceptions that reach the controller layer — Spring Security
 * and filter exceptions are handled earlier in the pipeline and never arrive
 * here.</li>
 * <li>Does not expose stack traces or internal class names to the caller; those
 * are logged server-side only at WARN or ERROR level.</li>
 * <li>The catch-all {@code handleGeneric} handler returns HTTP 500 and logs the
 * full stack trace so on-call engineers can diagnose unexpected failures
 * without exposing internals to the client.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.9 — created for API consistency (OCD Polish pass)</li>
 * <li>v2026.2.1 — added {@code NoResourceFoundException} handler to suppress
 * noisy 404 stack traces for static-resource misses</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.9
 * @version v2026.2.1
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	/**
	 * SLF4J logger for server-side exception details (never exposed to callers).
	 */
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Maps {@link BaseController.AuthException} (thrown by {@code requireAuth}) to
	 * HTTP 401.
	 *
	 * @param ex the authentication exception
	 * @return 401 with {@code {ok: false, error: "message"}}
	 * @since v2026.1.9
	 */
	@ExceptionHandler(BaseController.AuthException.class)
	public ResponseEntity<Map<String, Object>> handleAuthException(BaseController.AuthException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Maps {@link BaseController.ForbiddenException} (thrown by
	 * {@code requireAdminUser}) to HTTP 403.
	 *
	 * @param ex the authorisation exception
	 * @return 403 with {@code {ok: false, error: "message"}}
	 * @since v2026.1.9
	 */
	@ExceptionHandler(BaseController.ForbiddenException.class)
	public ResponseEntity<Map<String, Object>> handleForbiddenException(BaseController.ForbiddenException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Maps {@link java.util.NoSuchElementException} (resource not found in DB) to
	 * HTTP 404.
	 *
	 * @param ex the not-found exception carrying the resource identifier
	 * @return 404 with {@code {ok: false, error: "message"}}
	 * @since v2026.1.9
	 */
	@ExceptionHandler(NoSuchElementException.class)
	public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Maps {@link IllegalArgumentException} (invalid caller-supplied input) to HTTP
	 * 400.
	 *
	 * @param ex the bad-argument exception
	 * @return 400 with {@code {ok: false, error: "message"}}
	 * @since v2026.1.9
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Maps {@link IllegalStateException} (business-rule violation) to HTTP 409
	 * Conflict.
	 *
	 * @param ex the conflict exception
	 * @return 409 with {@code {ok: false, error: "message"}}
	 * @since v2026.1.9
	 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Handles requests where no handler mapping was found (404).
	 *
	 * @param ex the no-handler exception
	 * @return 404 with {@code {ok: false, error: "Not found"}}
	 */
	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "error", "Not found"));
	}

	/**
	 * Handles {@code 405 Method Not Allowed} errors uniformly.
	 *
	 * @param ex the method-not-supported exception
	 * @return 405 with {@code {ok: false, error: "Method not allowed"}}
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("ok", false, "error",
				"Method " + ex.getMethod() + " not allowed. Supported: " + ex.getSupportedHttpMethods()));
	}

	/**
	 * Handles 415 Unsupported Media Type — e.g. text/plain sent to a JSON endpoint.
	 *
	 * @param ex the unsupported media type exception
	 * @return 415 with {@code {ok: false, error: "Unsupported media type"}}
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(Map.of("ok", false, "error", "Unsupported media type: " + ex.getContentType()));
	}

	/**
	 * Handles malformed JSON request bodies (400).
	 *
	 * @param ex the message-not-readable exception
	 * @return 400 with {@code {ok: false, error: "Invalid JSON"}}
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("ok", false, "error", "Request body is malformed or missing"));
	}

	/**
	 * Maps a missing required request parameter
	 * ({@link MissingServletRequestParameterException}) to HTTP 400.
	 *
	 * <p>
	 * Without this handler the framework exception falls through to the generic
	 * catch-all and is reported as a misleading 500 Internal Server Error, even
	 * though the fault is caller-supplied (an omitted required query/form param).
	 *
	 * @param ex the missing-parameter exception
	 * @return 400 with {@code {ok: false, error: "Missing required parameter: name"}}
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("ok", false, "error", "Missing required parameter: " + ex.getParameterName()));
	}

	/**
	 * Maps {@link ProviderUnavailableException} (an optional external provider is
	 * not configured or unreachable) to HTTP 503 Service Unavailable.
	 *
	 * @param ex the provider-unavailable exception
	 * @return 503 with {@code {ok: false, error: "message"}}
	 */
	@ExceptionHandler(ProviderUnavailableException.class)
	public ResponseEntity<Map<String, Object>> handleProviderUnavailable(ProviderUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("ok", false, "error", ex.getMessage()));
	}

	/**
	 * Catch-all handler for any unhandled runtime exceptions.
	 *
	 * <p>
	 * Logs the full stack trace server-side but returns only a generic message to
	 * the caller to avoid leaking internal implementation details.
	 *
	 * @param ex the unhandled exception
	 * @return 500 with {@code {ok: false, error: "Internal server error"}}
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
		log.error("[global-error] Unhandled exception: {}", ex.getMessage(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("ok", false, "error", "Internal server error"));
	}
}
