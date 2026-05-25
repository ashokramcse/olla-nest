package com.ollanest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Global exception handler that converts unhandled exceptions into a consistent
 * {@code {ok: false, error: "message"}} JSON envelope.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Without this handler, Spring Boot returns its own {@code /error} page JSON
 * for unhandled exceptions, which has a different shape ({@code timestamp},
 * {@code status}, {@code error}, {@code path}) than the rest of the Olla Nest
 * API. This causes frontend consumers to fail on otherwise handled error paths.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Only maps exceptions that reach the controller layer — Spring Security
 * and filter exceptions are handled earlier in the pipeline.</li>
 * <li>Does not expose stack traces or internal class names to the caller;
 * those are logged server-side only.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.9 — added for API consistency (OCD Polish)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Handles requests where no handler mapping was found (404).
	 *
	 * @param ex the no-handler exception
	 * @return 404 with {@code {ok: false, error: "Not found"}}
	 */
	@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
	public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Map.of("ok", false, "error", "Not found"));
	}

	/**
	 * Handles {@code 405 Method Not Allowed} errors uniformly.
	 *
	 * @param ex the method-not-supported exception
	 * @return 405 with {@code {ok: false, error: "Method not allowed"}}
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(Map.of("ok", false, "error",
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
