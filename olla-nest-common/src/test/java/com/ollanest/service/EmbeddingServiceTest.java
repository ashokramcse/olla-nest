package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for the pure (deterministic, dependency-free) logic of
 * {@link EmbeddingService}: cosine similarity, keyword similarity, and the
 * vector ⇄ JSON round-trip.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The semantic-search ranking quality depends on these maths being correct;
 * regressions here silently degrade RAG retrieval. The remote {@code embed()}
 * call is excluded because it requires a live embedding backend.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A real {@link ObjectMapper} is used (the JSON helpers serialise with it);
 * the {@link JdbcTemplate} is a mock because the maths under test never touch
 * the database.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created to cover the previously-untested EmbeddingService.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@DisplayName("EmbeddingService — similarity + vector JSON")
class EmbeddingServiceTest {

	/** Service under test, built with a mock DB and a real JSON mapper. */
	private EmbeddingService svc;

	/**
	 * Builds the service with a mocked {@link JdbcTemplate} (unused by the pure
	 * maths) and a real {@link ObjectMapper} for the JSON helpers.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		svc = new EmbeddingService(mock(JdbcTemplate.class), new ObjectMapper());
	}

	/**
	 * Two identical vectors are maximally similar: cosine similarity must be
	 * (approximately) 1.0.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("cosineSimilarity of identical vectors ≈ 1.0")
	void identicalVectorsAreOne() {
		List<Double> v = List.of(1.0, 2.0, 3.0);
		assertThat(svc.cosineSimilarity(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
	}

	/**
	 * Orthogonal vectors share no direction: cosine similarity must be 0.0.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("cosineSimilarity of orthogonal vectors = 0.0")
	void orthogonalVectorsAreZero() {
		assertThat(svc.cosineSimilarity(List.of(1.0, 0.0), List.of(0.0, 1.0))).isZero();
	}

	/**
	 * Vectors of differing length (or empty) cannot be compared and must return 0.0
	 * rather than throwing.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("cosineSimilarity of mismatched/empty vectors = 0.0")
	void mismatchedVectorsAreZero() {
		assertThat(svc.cosineSimilarity(List.of(1.0, 2.0), List.of(1.0))).isZero();
		assertThat(svc.cosineSimilarity(List.of(), List.of())).isZero();
	}

	/**
	 * Keyword similarity is the fraction of unique query terms present in the chunk:
	 * a fully-covered query scores 1.0 and a half-covered query scores 0.5.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("keywordSimilarity = fraction of query terms found in chunk")
	void keywordSimilarityIsTermRecall() {
		assertThat(svc.keywordSimilarity("alpha beta", "the alpha and beta words")).isEqualTo(1.0);
		assertThat(svc.keywordSimilarity("alpha gamma", "only alpha here")).isEqualTo(0.5);
	}

	/**
	 * Null inputs to keyword similarity are handled gracefully, returning 0.0.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("keywordSimilarity with null inputs = 0.0")
	void keywordSimilarityNullSafe() {
		assertThat(svc.keywordSimilarity(null, "x")).isZero();
		assertThat(svc.keywordSimilarity("x", null)).isZero();
	}

	/**
	 * A vector serialised to JSON and parsed back must round-trip to the same
	 * values, so persisted embeddings reload faithfully.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("vectorToJson → jsonToVector round-trips")
	void vectorJsonRoundTrips() {
		List<Double> v = List.of(0.125, -0.5, 3.0);
		String json = svc.vectorToJson(v);
		assertThat(svc.jsonToVector(json)).containsExactlyElementsOf(v);
	}

	/**
	 * Parsing invalid JSON must yield an empty vector rather than throwing, so a
	 * corrupt stored embedding degrades to keyword search instead of crashing.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("jsonToVector of garbage → empty list")
	void jsonToVectorGarbageIsEmpty() {
		assertThat(svc.jsonToVector("not json")).isEmpty();
	}
}
