package com.ollanest.controller;

/**
 * Thrown when an external/optional provider needed to satisfy a request is not
 * configured or is unreachable.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Several features depend on optional providers — OpenAI for TTS/image
 * generation, the local Whisper server for transcription, etc. When one is
 * missing or down the request cannot be served, but that is an expected
 * operational state rather than a server fault. A dedicated exception lets the
 * error layer distinguish "provider not configured/unreachable" from genuine
 * bugs and respond accordingly.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Mapped to HTTP <b>503 Service Unavailable</b> by
 * {@link GlobalExceptionHandler} rather than a 500, so it stays out of
 * server-error rate metrics.</li>
 * <li>The message is actionable (it names the missing/unreachable provider) and
 * is surfaced in the 503 response body, signalling the client that the failure
 * is environmental.</li>
 * <li>Extends {@link RuntimeException} so callers are not forced to declare it;
 * it propagates to the global handler.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — initial extraction for 503 provider-unavailable mapping</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
public class ProviderUnavailableException extends RuntimeException {

	/** Serialization version id for this exception type. */
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a provider-unavailable exception with an actionable message naming the
	 * missing/unreachable provider (surfaced in the 503 response body).
	 *
	 * @param message the human-readable reason (e.g. "OpenAI API key not configured")
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	public ProviderUnavailableException(String message) {
		super(message);
	}
}
