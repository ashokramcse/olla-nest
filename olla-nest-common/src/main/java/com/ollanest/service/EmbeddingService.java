package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.*;

/**
 * Computes, stores, and compares vector embeddings for RAG document retrieval.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Vector embeddings are the core mechanism that enables semantic
 * (meaning-based) search over ingested documents in {@link RagService}. This
 * service encapsulates all embedding logic in one place: calling the Ollama
 * {@code /api/embed} endpoint to obtain high-dimensional vectors from text,
 * serialising/deserialising those vectors to JSON for database storage,
 * computing cosine similarity between query and chunk vectors, and providing a
 * lightweight keyword-overlap fallback for chunks that were ingested before an
 * embedding model was configured.
 *
 * <h3>Design notes</h3>
 * <p>
 * The active embedding model is read at call time from the
 * {@code embeddingModel} setting, defaulting to {@value #DEFAULT_EMBED_MODEL}.
 * A new {@link HttpClient} is created per {@link #embed(String)} call (rather
 * than a shared instance) to avoid thread-safety concerns with the 30-second
 * connect timeout.
 * <p>
 * The keyword similarity implementation is a simplified term-overlap ratio (not
 * true BM25) — sufficient as a fallback when embeddings are absent and avoids
 * introducing a ranking library dependency.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10 — HIGH-3 static shared HttpClient; L-2 retry logic for transient Ollama failures
 */
@Service
public class EmbeddingService {

	/** SLF4J logger for this class. */
	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

	/** Shared HTTP client for Ollama embedding API calls. Thread-safe; reuse avoids connection-pool thrashing. */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(30))
			.build();

	/**
	 * Ollama model name used when no {@code embeddingModel} setting is configured.
	 */
	private static final String DEFAULT_EMBED_MODEL = "nomic-embed-text";

	/**
	 * Spring JDBC template used to read the {@code ollamaUrl} and
	 * {@code embeddingModel} settings.
	 */
	private final JdbcTemplate db;

	/**
	 * Jackson mapper for serialising request bodies and deserialising embedding
	 * responses.
	 */
	private final ObjectMapper mapper;

	/** Fallback Ollama base URL injected from {@code application.properties}. */
	@Value("${ollama.url:http://localhost:11434}")
	private String ollamaUrlProp;

	/**
	 * Constructs the service with its required collaborators.
	 *
	 * @param db     Spring JDBC template bound to the application's data source
	 * @param mapper shared Jackson {@link ObjectMapper}
	 * @since v2026.1.0
	 */
	public EmbeddingService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/**
	 * Returns the active Ollama base URL, reading from the {@code settings} table
	 * first.
	 *
	 * <p>
	 * Falls back to the value injected from {@code application.properties} if no
	 * setting is found or the stored value is blank. Trailing slashes are stripped.
	 *
	 * @return cleaned Ollama base URL, never {@code null}
	 * @since v2026.1.0
	 */
	private String ollamaUrl() {
		try {
			List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = 'ollamaUrl'");
			if (!rows.isEmpty() && rows.get(0).get("value") != null) {
				String v = rows.get(0).get("value").toString().trim();
				if (!v.isEmpty())
					return v.replaceAll("/+$", "");
			}
		} catch (Exception ignored) {
		}
		return ollamaUrlProp.replaceAll("/+$", "");
	}

	/**
	 * Requests a vector embedding for the given text from the configured Ollama
	 * model.
	 *
	 * <p>
	 * Sends a {@code POST /api/embed} request with a 30-second connect timeout and
	 * a 60-second request timeout. If the embedding request fails for any reason
	 * (Ollama unreachable, model not loaded, invalid response, etc.) the failure is
	 * logged at WARN level and an empty list is returned so callers can fall back
	 * gracefully.
	 *
	 * @param text the text to embed; returns an empty list immediately if blank
	 * @return the embedding vector as a {@link List} of {@link Double} values;
	 *         empty list on any failure
	 * @since v2026.1.0
	 */
	public List<Double> embed(String text) {
		if (text == null || text.isBlank())
			return List.of();
		try {
			String model = embedModel();
			Map<String, Object> reqBody = Map.of("model", model, "input", text);
			String json = mapper.writeValueAsString(reqBody);
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ollamaUrl() + "/api/embed"))
					.timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json)).build();
			Exception lastEx = null;
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
					JsonNode root = mapper.readTree(resp.body());
					JsonNode embeddings = root.get("embeddings");
					if (embeddings != null && embeddings.isArray() && embeddings.size() > 0) {
						JsonNode vec = embeddings.get(0);
						List<Double> result = new ArrayList<>();
						for (JsonNode v : vec)
							result.add(v.asDouble());
						return result;
					}
					break; // got a response but no embeddings — don't retry
				} catch (Exception e) {
					lastEx = e;
					if (attempt < 2) {
						Thread.sleep(500);
					}
				}
			}
			if (lastEx != null) {
				log.warn("[embed] All 3 attempts failed: {}", lastEx.getMessage());
			}
		} catch (Exception e) {
			log.warn("[embed] Embedding failed: {}", e.getMessage());
		}
		return List.of();
	}

	/**
	 * Returns the name of the currently configured embedding model.
	 *
	 * <p>
	 * Reads the {@code embeddingModel} key from the {@code settings} table. Falls
	 * back to {@link #DEFAULT_EMBED_MODEL} if the setting is absent, blank, or the
	 * query fails.
	 *
	 * @return Ollama model name string such as {@code "nomic-embed-text"}
	 * @since v2026.1.0
	 */
	private String embedModel() {
		try {
			List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = 'embeddingModel'");
			if (!rows.isEmpty() && rows.get(0).get("value") != null) {
				String v = rows.get(0).get("value").toString().trim();
				if (!v.isEmpty())
					return v;
			}
		} catch (Exception ignored) {
		}
		return DEFAULT_EMBED_MODEL;
	}

	/**
	 * Computes the cosine similarity between two equal-length embedding vectors.
	 *
	 * <p>
	 * Returns {@code 0.0} when either vector is empty, when they have different
	 * lengths, or when either vector has zero magnitude (to avoid division by
	 * zero).
	 *
	 * @param a first embedding vector; must not be {@code null}
	 * @param b second embedding vector; must not be {@code null}
	 * @return cosine similarity in the range [0.0, 1.0]; {@code 0.0} on degenerate
	 *         input
	 * @since v2026.1.0
	 */
	public double cosineSimilarity(List<Double> a, List<Double> b) {
		if (a.isEmpty() || b.isEmpty() || a.size() != b.size())
			return 0.0;
		double dot = 0, normA = 0, normB = 0;
		for (int i = 0; i < a.size(); i++) {
			dot += a.get(i) * b.get(i);
			normA += a.get(i) * a.get(i);
			normB += b.get(i) * b.get(i);
		}
		double denom = Math.sqrt(normA) * Math.sqrt(normB);
		return denom == 0 ? 0.0 : dot / denom;
	}

	/**
	 * Estimates keyword relevance between a query and a chunk of text.
	 *
	 * <p>
	 * Tokenises both strings into lower-case alphanumeric terms of at least 3
	 * characters, then returns the fraction of unique query terms that appear in
	 * the chunk. This is a simplified term-recall metric (not BM25) used as a
	 * fallback when embeddings are unavailable.
	 *
	 * @param query the search query; {@code null} returns {@code 0.0}
	 * @param chunk the document chunk to compare against; {@code null} returns
	 *              {@code 0.0}
	 * @return term-recall ratio in [0.0, 1.0]; {@code 0.0} when the query is empty
	 * @since v2026.1.0
	 */
	public double keywordSimilarity(String query, String chunk) {
		if (query == null || chunk == null)
			return 0.0;
		Set<String> queryTerms = tokenize(query.toLowerCase());
		Set<String> chunkTerms = tokenize(chunk.toLowerCase());
		long hits = queryTerms.stream().filter(chunkTerms::contains).count();
		return queryTerms.isEmpty() ? 0.0 : (double) hits / queryTerms.size();
	}

	/**
	 * Tokenises a lower-case string into a set of alphanumeric terms of at least 3
	 * characters.
	 *
	 * <p>
	 * Non-alphanumeric characters are treated as delimiters. Short tokens (1–2
	 * characters) are discarded to reduce noise from common stop-words.
	 *
	 * @param text lower-case text to tokenise
	 * @return set of unique term strings; never {@code null}
	 * @since v2026.1.0
	 */
	private Set<String> tokenize(String text) {
		Set<String> terms = new HashSet<>();
		for (String word : text.split("[^a-z0-9]+")) {
			if (word.length() > 2)
				terms.add(word);
		}
		return terms;
	}

	/**
	 * Serialises an embedding vector to a compact JSON array string.
	 *
	 * <p>
	 * Returns {@code "[]"} if Jackson serialisation fails for any reason.
	 *
	 * @param vec the embedding vector to serialise; must not be {@code null}
	 * @return JSON array string such as {@code "[0.123,0.456,...]"}
	 * @since v2026.1.0
	 */
	public String vectorToJson(List<Double> vec) {
		try {
			return mapper.writeValueAsString(vec);
		} catch (Exception e) {
			return "[]";
		}
	}

	/**
	 * Deserialises a JSON array string back into an embedding vector.
	 *
	 * <p>
	 * Returns an empty list for {@code null}, blank, or {@code "[]"} input, and
	 * also on any Jackson deserialisation error. All numeric values are converted
	 * to {@link Double}.
	 *
	 * @param json the JSON array string produced by {@link #vectorToJson(List)}
	 * @return the embedding vector; empty list on any error or empty input
	 * @since v2026.1.0
	 */
	@SuppressWarnings("unchecked")
	public List<Double> jsonToVector(String json) {
		try {
			if (json == null || json.isBlank() || "[]".equals(json.trim()))
				return List.of();
			return (List<Double>) mapper.readValue(json, List.class).stream().map(v -> ((Number) v).doubleValue())
					.collect(Collectors.toList());
		} catch (Exception e) {
			return List.of();
		}
	}
}
