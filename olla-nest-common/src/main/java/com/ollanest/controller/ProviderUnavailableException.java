package com.ollanest.controller;

/**
 * Thrown when an external/optional provider needed to satisfy a request is not
 * configured or is unreachable (e.g. no OpenAI API key for TTS or image
 * generation, or the local Whisper server is down).
 *
 * <p>
 * This is an expected operational state — not a server fault — so it is mapped
 * to HTTP <b>503 Service Unavailable</b> by {@link GlobalExceptionHandler}
 * rather than a 500. That keeps "provider not configured" out of server-error
 * rate metrics and tells the client the failure is environmental, not a bug.
 *
 * @author Ashok Ram
 */
public class ProviderUnavailableException extends RuntimeException {

	public ProviderUnavailableException(String message) {
		super(message);
	}
}
