package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for the pure (deterministic, network-free) logic of
 * {@link OllamaService}: base-URL cleaning, capability inference, and the
 * size/name-based score heuristics.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The router scores and capability tags produced here drive model selection, so
 * regressions silently change which model handles a request. The HTTP methods
 * ({@code fetchOllamaModels}, {@code ping}, {@code syncOllamaModels}) are
 * excluded because they need a live Ollama server.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All collaborators are mocks (a real {@link ObjectMapper} where one is
 * needed); the methods under test are pure functions of their arguments.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created to cover the previously-untested OllamaService.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@DisplayName("OllamaService — URL cleaning, capabilities, scores")
class OllamaServiceTest {

	/** Service under test, built with mocked collaborators. */
	private OllamaService svc;

	/**
	 * Builds the service with mocked JDBC/database/mapper collaborators; the pure
	 * methods under test do not exercise them.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		svc = new OllamaService(mock(JdbcTemplate.class), mock(DatabaseService.class), new ObjectMapper());
	}

	/**
	 * Base-URL cleaning strips one or more trailing slashes so URL concatenation
	 * never produces a double slash, while leaving a clean URL unchanged.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("cleanBaseUrl strips trailing slashes")
	void cleanBaseUrlStripsTrailingSlashes() {
		assertThat(svc.cleanBaseUrl("http://host:11434/")).isEqualTo("http://host:11434");
		assertThat(svc.cleanBaseUrl("http://host:11434///")).isEqualTo("http://host:11434");
		assertThat(svc.cleanBaseUrl("http://host:11434")).isEqualTo("http://host:11434");
	}

	/**
	 * Every model gets the baseline {@code general}/{@code ask} capabilities, and a
	 * coder-family name additionally infers the coding capability set.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("inferCapabilities adds coding caps for coder models")
	void inferCapabilitiesCoder() {
		assertThat(svc.inferCapabilities("deepseek-coder:6.7b")).contains("general", "ask", "coding", "debugging",
				"build");
	}

	/**
	 * A vision/OCR-family model name infers the vision capability set.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("inferCapabilities adds vision caps for llava models")
	void inferCapabilitiesVision() {
		assertThat(svc.inferCapabilities("llava:13b")).contains("ocr", "vision", "document");
	}

	/**
	 * An unrecognised model name still receives the baseline capabilities so the
	 * router can always route it as a general model.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("inferCapabilities always includes the general baseline")
	void inferCapabilitiesBaseline() {
		assertThat(svc.inferCapabilities("some-unknown-model")).contains("general", "ask");
	}

	/**
	 * A small model (&lt; 2 GB) scores a high speed value, and both returned scores
	 * are clamped to the documented [10, 100] range.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("inferScores gives small models high speed, scores in [10,100]")
	void inferScoresSmallModelFast() {
		int[] scores = svc.inferScores("gemma:2b", 1L * 1024 * 1024 * 1024); // 1 GB
		assertThat(scores[0]).isEqualTo(95); // speed
		assertThat(scores[0]).isBetween(10, 100);
		assertThat(scores[1]).isBetween(10, 100);
	}

	/**
	 * A large model scores a lower speed value than a small model, reflecting the
	 * size-inverse speed heuristic.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("inferScores gives large models lower speed than small models")
	void inferScoresLargeModelSlower() {
		int small = svc.inferScores("x", 1L * 1024 * 1024 * 1024)[0];
		int large = svc.inferScores("x", 30L * 1024 * 1024 * 1024)[0];
		assertThat(large).isLessThan(small);
	}
}
