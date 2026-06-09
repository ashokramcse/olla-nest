package com.ollanest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages Ollama model discovery, capability inference, and health checks.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest supports locally-running Ollama as a first-class LLM provider. This
 * service is the single integration point: it polls Ollama's REST API on a
 * fixed schedule, persists discovered models into the {@code models} table,
 * infers capabilities and quality/speed scores from model name heuristics, and
 * exposes a lightweight {@code ping()} check used by the health endpoint. It
 * deliberately does NOT handle chat inference — that belongs to
 * {@link ProviderService}.
 *
 * <h3>Design notes</h3>
 * <p>
 * A background {@link Scheduled} task runs every 60 s (5 s initial delay) to
 * reconcile the local DB with whatever Ollama currently has loaded. If Ollama
 * is unreachable all rows with {@code provider='ollama'} are flipped to
 * {@code status='offline'} so the router skips them gracefully. Models no
 * longer reported by Ollama are set to {@code status='missing'}.
 * <p>
 * The {@code HttpClient} is constructed once in the constructor with a 5-second
 * connect timeout; individual request timeouts are set per call.
 * <p>
 * Context-window size is fetched via {@code POST /api/show} and stored in the
 * {@code context_size} column; failures are silently ignored and the column is
 * left null.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@Service
public class OllamaService {

	/** SLF4J logger for this class. */
	private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

	/** Spring JDBC template for direct SQL access to the SQLite database. */
	private final JdbcTemplate db;

	/** Service for reading persisted settings (e.g. {@code ollamaUrl}). */
	private final DatabaseService databaseService;

	/**
	 * Jackson mapper used for serialising request bodies and parsing Ollama JSON
	 * responses.
	 */
	private final ObjectMapper mapper;

	/** Shared HTTP client; constructed once with a 5 s connect timeout. */
	private final HttpClient httpClient;

	/** Fallback Ollama base URL injected from {@code application.properties}. */
	@Value("${ollama.url:http://localhost:11434}")
	private String defaultOllamaUrl;

	/**
	 * Constructs the service and initialises the shared {@link HttpClient}.
	 *
	 * @param db              Spring JDBC template for the application database
	 * @param databaseService service for reading persisted key-value settings
	 * @param mapper          shared Jackson {@link ObjectMapper}
	 * @since v2026.1.0
	 */
	public OllamaService(JdbcTemplate db, DatabaseService databaseService, ObjectMapper mapper) {
		this.db = db;
		this.databaseService = databaseService;
		this.mapper = mapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	/**
	 * Returns the active Ollama base URL, stripping any trailing slashes.
	 *
	 * <p>
	 * Reads the {@code ollamaUrl} setting from the database, falling back to the
	 * value injected from {@code application.properties} if no setting is stored.
	 *
	 * @return cleaned base URL string such as {@code http://localhost:11434}
	 * @since v2026.1.0
	 */
	public String ollamaUrl() {
		String url = databaseService.getSetting("ollamaUrl", defaultOllamaUrl);
		return cleanBaseUrl(url);
	}

	/**
	 * Removes trailing forward-slashes from a URL string.
	 *
	 * <p>
	 * Ensures that callers can safely append path segments with a leading {@code /}
	 * without creating double-slash URLs. Returns {@link #defaultOllamaUrl} when
	 * {@code url} is {@code null}.
	 *
	 * @param url raw URL string, may be {@code null} or end with one or more
	 *            {@code /}
	 * @return cleaned URL without trailing slashes, never {@code null}
	 * @since v2026.1.0
	 */
	public String cleanBaseUrl(String url) {
		if (url == null)
			return defaultOllamaUrl;
		return url.replaceAll("/+$", "");
	}

	/**
	 * Spring-scheduled task that runs {@link #syncOllamaModels()} every 60 seconds.
	 *
	 * <p>
	 * The initial delay of 5 seconds gives the application time to finish startup
	 * before the first network call is made. Errors are caught and logged at WARN
	 * level so that a temporarily unreachable Ollama instance does not crash the
	 * scheduler.
	 *
	 * @since v2026.1.0
	 */
	@Scheduled(fixedDelay = 60000, initialDelay = 5000)
	public void scheduledSync() {
		try {
			syncOllamaModels();
		} catch (Exception e) {
			log.warn("[ollama-sync] Scheduled sync error: {}", e.getMessage());
		}
	}

	/**
	 * Fetches installed Ollama models and reconciles them with the local database.
	 *
	 * <p>
	 * For each model reported by Ollama this method upserts a row in the
	 * {@code models} table, setting {@code provider='ollama'}, inferred
	 * capabilities, speed/quality scores, and the model's context window size
	 * (queried from {@code /api/show}). Models that were previously in the DB but
	 * are no longer installed are set to {@code status='missing'}. If Ollama is
	 * unreachable all Ollama rows are set to {@code status='offline'} and the
	 * method returns {@code ok:false}.
	 *
	 * <p>
	 * After upserting, {@link #ensureDefaultAccess()} is called to bootstrap access
	 * grants when none exist yet.
	 *
	 * @return a map with keys {@code ok} (boolean), {@code models} (list of raw
	 *         Ollama model maps), and optionally {@code error} (string) on failure
	 * @since v2026.1.0
	 */
	public Map<String, Object> syncOllamaModels() {
		String baseUrl = ollamaUrl();
		List<Map<String, Object>> installed;
		try {
			installed = fetchOllamaModels(baseUrl);
		} catch (Exception e) {
			db.update("UPDATE models SET status = 'offline' WHERE provider = 'ollama' AND status != 'disabled'");
			log.warn("[ollama-sync] Unreachable at {}: {}", baseUrl, e.getMessage());
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("ok", false);
			result.put("models", List.of());
			result.put("error", e.getMessage());
			return result;
		}

		List<String> seenIds = new ArrayList<>();
		String now = Instant.now().toString();

		for (Map<String, Object> item : installed) {
			String modelRef = (String) item.get("name");
			if (modelRef == null)
				continue;
			long sizeBytes = item.containsKey("size") ? ((Number) item.get("size")).longValue() : 0;
			int[] scores = inferScores(modelRef, sizeBytes);
			Long contextSize = null;
			try {
				contextSize = fetchOllamaModelInfo(baseUrl, modelRef);
			} catch (Exception ignored) {
			}

			String modelId = "ollama:" + modelRef;
			String capJson = mapper.valueToTree(inferCapabilities(modelRef)).toString();

			db.update(
					"INSERT INTO models (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, "
							+ "privacy, context_size, last_seen_at) "
							+ "VALUES (?, ?, 'ollama', ?, 'available', ?, ?, ?, 'local', ?, ?) "
							+ "ON CONFLICT(id) DO UPDATE SET name=excluded.name, provider=excluded.provider, "
							+ "model_ref=excluded.model_ref, status=excluded.status, capabilities=excluded.capabilities, "
							+ "speed_score=excluded.speed_score, quality_score=excluded.quality_score, privacy=excluded.privacy, "
							+ "context_size=excluded.context_size, last_seen_at=excluded.last_seen_at",
					modelId, modelRef, modelRef, capJson, scores[0], scores[1], contextSize, now);

			seenIds.add(modelId);
		}

		if (!seenIds.isEmpty()) {
			String placeholders = String.join(",", Collections.nCopies(seenIds.size(), "?"));
			List<Object> params = new ArrayList<>(seenIds);
			db.update("UPDATE models SET status = 'missing' WHERE provider = 'ollama' AND id NOT IN (" + placeholders
					+ ")", params.toArray());
		} else {
			db.update("UPDATE models SET status = 'missing' WHERE provider = 'ollama'");
		}

		ensureDefaultAccess();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("models", installed);
		return result;
	}

	/**
	 * Bootstraps access grants for all Ollama models when no grants exist yet.
	 *
	 * <p>
	 * On a fresh installation the {@code access_grants} table is empty. This method
	 * inserts a "group-all can use" grant for every Ollama model so that users can
	 * start chatting immediately without manual admin setup. Once any grant exists
	 * the method exits without making any changes.
	 *
	 * @since v2026.1.0
	 */
	private void ensureDefaultAccess() {
		Integer grantCount = db.queryForObject("SELECT COUNT(*) FROM access_grants", Integer.class);
		if (grantCount != null && grantCount > 0)
			return;
		List<String> ollamaModels = db.queryForList("SELECT id FROM models WHERE provider = 'ollama'", String.class);
		if (ollamaModels.isEmpty())
			return;
		for (String modelId : ollamaModels) {
			db.update("INSERT INTO access_grants (id, subject_type, subject_id, model_id, can_use) "
					+ "VALUES (?, 'group', 'group-all', ?, 1)", uid("grant"), modelId);
		}
	}

	/**
	 * Calls {@code GET /api/tags} on the given Ollama base URL and returns the list
	 * of models.
	 *
	 * <p>
	 * Each map in the returned list contains raw fields from the Ollama API
	 * response such as {@code name}, {@code size}, {@code digest}, and
	 * {@code details}. Throws if the HTTP status code is not 200 or if the request
	 * times out (3-second timeout).
	 *
	 * @param baseUrl Ollama base URL without trailing slash, e.g.
	 *                {@code http://localhost:11434}
	 * @return list of raw model maps; empty list if the {@code models} array is
	 *         absent
	 * @throws Exception if the HTTP request fails, times out, or Ollama returns a
	 *                   non-200 status
	 * @since v2026.1.0
	 */
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> fetchOllamaModels(String baseUrl) throws Exception {
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(cleanBaseUrl(baseUrl) + "/api/tags"))
				.timeout(Duration.ofSeconds(3)).GET().build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200)
			throw new RuntimeException("Ollama returned " + resp.statusCode());
		JsonNode data = mapper.readTree(resp.body());
		JsonNode modelsNode = data.get("models");
		List<Map<String, Object>> result = new ArrayList<>();
		if (modelsNode != null && modelsNode.isArray()) {
			for (JsonNode n : modelsNode) {
				result.add(mapper.convertValue(n, Map.class));
			}
		}
		return result;
	}

	/**
	 * Queries {@code POST /api/show} for a model's declared context-window length.
	 *
	 * <p>
	 * Inspects the {@code modelinfo} object for any field ending in
	 * {@code .context_length}; falls back to parsing a {@code num_ctx} line from
	 * the {@code parameters} string if the field is absent. Returns {@code null} on
	 * any error or when the value cannot be determined.
	 *
	 * @param baseUrl   Ollama base URL without trailing slash
	 * @param modelName Ollama model reference such as {@code llama3.2:latest}
	 * @return context-window size in tokens, or {@code null} if unknown
	 * @since v2026.1.0
	 */
	public Long fetchOllamaModelInfo(String baseUrl, String modelName) {
		try {
			String body = mapper.writeValueAsString(Map.of("name", modelName));
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(cleanBaseUrl(baseUrl) + "/api/show"))
					.timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200)
				return null;
			JsonNode data = mapper.readTree(resp.body());
			JsonNode modelinfo = data.get("modelinfo");
			if (modelinfo != null) {
				for (var entry : modelinfo.properties()) {
					if (entry.getKey().endsWith(".context_length")) {
						long val = entry.getValue().asLong(0);
						if (val > 0)
							return val;
					}
				}
			}
			// Fallback: parameters string
			JsonNode params = data.get("parameters");
			if (params != null) {
				Matcher m = Pattern.compile("num_ctx\\s+(\\d+)").matcher(params.asText(""));
				if (m.find())
					return Long.parseLong(m.group(1));
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Infers a list of capability tags from the model's name string.
	 *
	 * <p>
	 * Uses simple regex matching on the lowercased model name to assign tags such
	 * as {@code coding}, {@code reasoning}, {@code vision}, {@code medical}, etc.
	 * Every model receives at least {@code general} and {@code ask}. These tags are
	 * stored as a JSON array in the {@code capabilities} column and drive the
	 * auto-router's scoring.
	 *
	 * @param modelName Ollama model reference string (e.g.
	 *                  {@code qwen2.5-coder:7b})
	 * @return ordered list of capability tag strings, never {@code null} or empty
	 * @since v2026.1.0
	 */
	public List<String> inferCapabilities(String modelName) {
		String text = modelName.toLowerCase();
		Set<String> caps = new LinkedHashSet<>(Arrays.asList("general", "ask"));
		if (text.matches(".*?(qwen|coder|code|deepseek|starcoder|devstral).*"))
			caps.addAll(Arrays.asList("coding", "debugging", "build", "review", "project"));
		if (text.matches(".*?(think|reason|r1|qwq|gemma|llama|mistral|granite).*"))
			caps.addAll(Arrays.asList("reasoning", "analysis", "learn"));
		if (text.matches(".*?(gemma|llama|mistral|granite|phi).*"))
			caps.addAll(Arrays.asList("writing", "summary"));
		if (text.matches(".*?(ocr|vision|vl|llava|minicpm-v|bakllava).*"))
			caps.addAll(Arrays.asList("ocr", "vision", "document"));
		if (text.matches(".*?(med|clinical|health).*"))
			caps.addAll(Arrays.asList("medical", "summary", "analysis"));
		return new ArrayList<>(caps);
	}

	/**
	 * Computes heuristic speed and quality scores for a model based on its name and
	 * disk size.
	 *
	 * <p>
	 * Speed is derived from the model's size on disk: smaller models score higher
	 * because they typically run faster on consumer hardware. Quality starts from a
	 * size-based baseline and is bumped upward for models whose names suggest
	 * strong reasoning or specialist ability. Both scores are clamped to the range
	 * [10, 100].
	 *
	 * @param modelName Ollama model reference (used for name-pattern quality bonus)
	 * @param sizeBytes model file size in bytes as reported by Ollama; pass
	 *                  {@code 0} if unknown
	 * @return two-element array {@code [speedScore, qualityScore]}, each in [10,
	 *         100]
	 * @since v2026.1.0
	 */
	public int[] inferScores(String modelName, long sizeBytes) {
		String text = modelName.toLowerCase();
		double sizeGb = sizeBytes / (1024.0 * 1024.0 * 1024.0);
		int speedScore;
		if (sizeBytes > 0 && sizeGb < 2)
			speedScore = 95;
		else if (sizeBytes > 0 && sizeGb < 5)
			speedScore = 78;
		else if (sizeBytes > 0 && sizeGb < 10)
			speedScore = 58;
		else if (sizeBytes > 0 && sizeGb < 20)
			speedScore = 38;
		else
			speedScore = 25;

		int qualityScore = sizeBytes > 0 ? (int) Math.min(95, 45 + Math.round(sizeGb * 3)) : 60;
		if (text.matches(".*?(think|reason|qwen|gemma|llama|mistral|granite).*"))
			qualityScore += 8;
		if (text.matches(".*?(ocr|med|coder|code).*"))
			qualityScore += 6;

		return new int[] { Math.max(10, Math.min(100, speedScore)), Math.max(10, Math.min(100, qualityScore)) };
	}

	/**
	 * Returns {@code true} if Ollama is reachable and responding with HTTP 200.
	 *
	 * <p>
	 * Sends a lightweight {@code GET /api/tags} request with a 2-second timeout.
	 * Any exception (connection refused, timeout, etc.) is caught and returns
	 * {@code false}. This method is safe to call from health-check endpoints.
	 *
	 * @return {@code true} if Ollama is up, {@code false} otherwise
	 * @since v2026.1.0
	 */
	public boolean ping() {
		try {
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ollamaUrl() + "/api/tags"))
					.timeout(Duration.ofSeconds(2)).GET().build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			return resp.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Generates a short random identifier with the given prefix.
	 *
	 * <p>
	 * Combines the current epoch millisecond and a random component, both encoded
	 * in base-36, to produce a compact, monotonically-increasing unique string.
	 *
	 * @param prefix string prepended to the generated ID, e.g. {@code "grant"}
	 * @return identifier such as {@code grant-lx7k3a-p2q8r5}
	 * @since v2026.1.0
	 */
	private String uid(String prefix) {
		return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString((long) (Math.random() * 36 * 36 * 36 * 36 * 36 * 36), 36);
	}
}
