package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Provider call layer — resolves the active provider and executes both blocking
 * and streaming LLM inference requests.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest supports multiple LLM backends (Ollama, Anthropic, OpenAI, Groq,
 * and arbitrary OpenAI-compatible endpoints). Rather than scattering HTTP logic
 * throughout controllers and pipeline services, all provider-specific wiring
 * lives here. Callers receive a uniform {@link ProviderResult} or stream their
 * tokens via a {@link Consumer} without needing to know which wire protocol is
 * in use. This mirrors the JavaScript {@code src/services/providers.js} module
 * from the prior Node.js implementation.
 *
 * <h3>Design notes</h3>
 * <p>
 * A single shared {@link HttpClient} is constructed in the constructor with a
 * 10-second connect timeout; per-request timeouts are set individually
 * (streaming calls use a 5-minute timeout to accommodate large model outputs).
 * <p>
 * {@link FunctionCallService} is injected via a setter rather than the
 * constructor to break the circular dependency that would otherwise occur
 * ({@code ProviderService} → {@code FunctionCallService} →
 * {@code ProviderService}).
 * <p>
 * API keys are stored encrypted; {@link CryptoService} handles decryption
 * transparently so that keys are never logged or held in plain text longer than
 * necessary.
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
public class ProviderService {

	/** SLF4J logger for this class. */
	private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

	/** Spring JDBC template for reading provider rows from the database. */
	private final JdbcTemplate db;

	/** Encrypts and decrypts provider API keys stored at rest. */
	private final CryptoService cryptoService;

	/** Ollama integration service used to resolve the Ollama base URL. */
	private final OllamaService ollamaService;

	/** Jackson mapper for serialising request bodies and parsing JSON responses. */
	private final ObjectMapper mapper;

	/** Shared HTTP client with a 10-second connect timeout. */
	private final HttpClient httpClient;

	/**
	 * Set via {@link #setFunctionCallService(FunctionCallService)} after
	 * construction to avoid a circular Spring bean dependency.
	 */
	private FunctionCallService functionCallService;

	/**
	 * Constructs the service and initialises the shared {@link HttpClient}.
	 *
	 * @param db            Spring JDBC template for the application database
	 * @param cryptoService service for AES key encryption/decryption
	 * @param ollamaService service providing the resolved Ollama base URL
	 * @param mapper        shared Jackson {@link ObjectMapper}
	 * @since v2026.1.0
	 */
	public ProviderService(JdbcTemplate db, CryptoService cryptoService, OllamaService ollamaService,
			ObjectMapper mapper) {
		this.db = db;
		this.cryptoService = cryptoService;
		this.ollamaService = ollamaService;
		this.mapper = mapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	/**
	 * Setter-injected to resolve the circular dependency between
	 * {@link ProviderService} and {@link FunctionCallService}.
	 *
	 * @param functionCallService the function-call parsing service
	 * @since v2026.1.0
	 */
	@org.springframework.beans.factory.annotation.Autowired
	public void setFunctionCallService(FunctionCallService functionCallService) {
		this.functionCallService = functionCallService;
	}

	// ── Inner types ─────────────────────────────────────────────────────────

	/**
	 * Carries the result of a blocking (non-streaming) provider call.
	 *
	 * <p>
	 * Tool-call responses (when the model returns structured function invocations
	 * instead of plain text) are surfaced via the {@link #toolCalls} field; it is
	 * {@code null} for ordinary text completions.
	 *
	 * @since v2026.1.0
	 */
	public static class ProviderResult {

		/** The text completion returned by the model. */
		public String content;

		/** Total tokens consumed by the request (input + output where reported). */
		public int tokensUsed;

		/** Human-readable name of the provider that fulfilled the request. */
		public String providerName;

		/**
		 * Non-{@code null} when the model responded with structured tool calls instead
		 * of (or in addition to) plain text; each map contains at least {@code name}
		 * and {@code arguments} keys.
		 */
		public List<Map<String, Object>> toolCalls;

		/**
		 * Constructs a result with the given content, token count, and provider name.
		 *
		 * @param content      the model's text output
		 * @param tokensUsed   total tokens consumed
		 * @param providerName display name of the fulfilling provider
		 */
		public ProviderResult(String content, int tokensUsed, String providerName) {
			this.content = content;
			this.tokensUsed = tokensUsed;
			this.providerName = providerName;
		}
	}

	// ── Public API ───────────────────────────────────────────────────────────

	/**
	 * Resolves a provider configuration map from a routing decision.
	 *
	 * <p>
	 * For the built-in {@code ollama} provider a synthetic map is returned using
	 * the current Ollama URL from settings. For all other providers the
	 * {@code api_providers} table is queried; a {@link RuntimeException} is thrown
	 * if the provider is not found or is disabled.
	 *
	 * @param route the routing decision produced by
	 *              {@link RouterService#routeModel}
	 * @return a map containing at minimum {@code type}, {@code base_url},
	 *         {@code api_key_enc}, and {@code name}
	 * @throws RuntimeException if the provider record is missing or disabled
	 * @since v2026.1.0
	 */
	public Map<String, Object> resolveProvider(RouterService.RouteResult route) {
		ModelRecord selected = route.selected;
		if ("ollama".equals(selected.provider)) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("type", "ollama");
			p.put("base_url", ollamaService.ollamaUrl());
			p.put("api_key_enc", cryptoService.encryptKey(""));
			p.put("name", "Ollama");
			return p;
		}
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM api_providers WHERE id = ?", selected.provider);
		if (rows.isEmpty()) {
			throw new RuntimeException(
					"Provider '" + selected.provider + "' is not configured. Add it in Admin → Providers.");
		}
		Map<String, Object> p = rows.get(0);
		Object enabled = p.get("enabled");
		boolean isEnabled = enabled != null && ((Number) enabled).intValue() != 0;
		if (!isEnabled) {
			throw new RuntimeException("Provider '" + p.get("name") + "' is disabled. Enable it in Admin → Providers.");
		}
		return p;
	}

	/**
	 * Executes a blocking (non-streaming) LLM request without tool definitions.
	 *
	 * <p>
	 * Convenience overload that delegates to
	 * {@link #callProvider(Map, String, List, int, List)} with
	 * {@code tools = null}.
	 *
	 * @param provider  provider configuration map from {@link #resolveProvider}
	 * @param modelId   the model reference to pass to the provider (e.g.
	 *                  {@code llama3.2:3b})
	 * @param messages  ordered list of chat messages; each map must have
	 *                  {@code role} and {@code content} keys
	 * @param timeoutMs per-request timeout in milliseconds; values &le; 0 default
	 *                  to 300 000 ms (5 minutes)
	 * @return the provider's completion result
	 * @throws Exception if the HTTP request fails, times out, or the provider
	 *                   returns a non-200 status
	 * @since v2026.1.0
	 */
	public ProviderResult callProvider(Map<String, Object> provider, String modelId, List<Map<String, Object>> messages,
			int timeoutMs) throws Exception {
		return callProvider(provider, modelId, messages, timeoutMs, null);
	}

	/**
	 * Executes a blocking (non-streaming) LLM request, optionally with tool
	 * definitions.
	 *
	 * <p>
	 * Dispatches to the appropriate provider implementation based on the
	 * {@code type} field of {@code provider}: {@code "ollama"},
	 * {@code "anthropic"}, or any OpenAI-compatible type ({@code "openai"},
	 * {@code "groq"}, or custom). Responses are parsed and normalised into a
	 * {@link ProviderResult}.
	 *
	 * <p>
	 * For Ollama calls the model output is cleaned via
	 * {@link WorkspaceService#cleanModelOutput(String)} to remove artefacts.
	 * Tool-call responses are parsed by {@link FunctionCallService#parseToolCalls}
	 * and stored in {@link ProviderResult#toolCalls}.
	 *
	 * @param provider  provider configuration map from {@link #resolveProvider}
	 * @param modelId   the model reference to pass to the provider
	 * @param messages  ordered list of chat messages
	 * @param timeoutMs per-request timeout in milliseconds; &le; 0 defaults to 5
	 *                  minutes
	 * @param tools     optional list of tool definition maps; {@code null} or empty
	 *                  to disable tool calling
	 * @return the normalised provider result
	 * @throws Exception if the HTTP request fails, times out, or the provider
	 *                   returns a non-200 status
	 * @since v2026.1.0
	 */
	public ProviderResult callProvider(Map<String, Object> provider, String modelId, List<Map<String, Object>> messages,
			int timeoutMs, List<Map<String, Object>> tools) throws Exception {
		String type = (String) provider.get("type");
		String apiKey = cryptoService.decryptKey((String) provider.get("api_key_enc"));

		if ("ollama".equals(type)) {
			String base = ollamaService.cleanBaseUrl((String) provider.get("base_url"));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("messages", messages);
			body.put("stream", false);
			Map<String, Object> options = new LinkedHashMap<>();
			options.put("temperature", 0.5);
			options.put("num_predict", 4096);
			body.put("options", options);
			if (tools != null && !tools.isEmpty()) {
				body.put("tools", tools);
			}
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + "/api/chat"))
					.timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				RuntimeException ex = new RuntimeException("Ollama " + resp.statusCode());
				throw ex;
			}
			JsonNode data = mapper.readTree(resp.body());
			JsonNode messageNode = data.get("message");
			String content = "";
			if (messageNode != null && messageNode.has("content"))
				content = messageNode.get("content").asText("");
			else if (data.has("response"))
				content = data.get("response").asText("");
			int tokens = data.has("eval_count") ? data.get("eval_count").asInt(0) : 0;
			ProviderResult result = new ProviderResult(WorkspaceService.cleanModelOutput(content), tokens,
					(String) provider.getOrDefault("name", "Ollama"));
			// Parse tool calls if present
			if (messageNode != null && messageNode.has("tool_calls")) {
				FunctionCallService fcs = functionCallService;
				if (fcs != null)
					result.toolCalls = fcs.parseToolCalls(messageNode);
			}
			return result;

		} else if ("anthropic".equals(type)) {
			String base = ollamaService
					.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.anthropic.com"));
			List<Map<String, Object>> anthropicMessages = new ArrayList<>();
			String systemContent = "";
			for (Map<String, Object> m : messages) {
				if ("system".equals(m.get("role")))
					systemContent = (String) m.get("content");
				else
					anthropicMessages.add(m);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("max_tokens", 4096);
			body.put("system", systemContent);
			body.put("messages", anthropicMessages);
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + "/v1/messages"))
					.timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
					.header("Content-Type", "application/json").header("anthropic-version", "2023-06-01")
					.header("x-api-key", apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200)
				throw new RuntimeException("Anthropic " + resp.statusCode());
			JsonNode data = mapper.readTree(resp.body());
			String content = data.has("content") && data.get("content").isArray() && data.get("content").size() > 0
					? data.get("content").get(0).get("text").asText("")
					: "";
			int tokens = 0;
			if (data.has("usage")) {
				tokens = data.get("usage").path("input_tokens").asInt(0)
						+ data.get("usage").path("output_tokens").asInt(0);
			}
			return new ProviderResult(content, tokens, "Anthropic");

		} else {
			// openai / groq / custom
			String base = resolveOpenAIBase(provider, type);
			String url = "groq".equals(type) ? base + "/v1/chat/completions" : base + "/chat/completions";
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("messages", messages);
			body.put("stream", false);
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
					.timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
					.header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200)
				throw new RuntimeException("Provider " + resp.statusCode());
			JsonNode data = mapper.readTree(resp.body());
			String content = "";
			if (data.has("choices") && data.get("choices").isArray() && data.get("choices").size() > 0) {
				content = data.get("choices").get(0).path("message").path("content").asText("");
			}
			int tokens = data.has("usage") ? data.get("usage").path("total_tokens").asInt(0) : 0;
			String name = (String) provider.getOrDefault("name", type);
			return new ProviderResult(content, tokens, name);
		}
	}

	/**
	 * Executes a streaming LLM request and delivers tokens incrementally via a
	 * callback.
	 *
	 * <p>
	 * Dispatches to the appropriate provider protocol: Ollama NDJSON, Anthropic
	 * Server-Sent Events, or OpenAI-compatible SSE. Each incoming token is passed
	 * to {@code onToken}; when the stream ends (or on error) {@code onDone} is
	 * called with the total token count (which may be {@code 0} for providers that
	 * do not report token usage in the stream).
	 *
	 * @param provider provider configuration map from {@link #resolveProvider}
	 * @param modelId  the model reference string
	 * @param messages ordered list of chat messages
	 * @param onToken  callback invoked for each non-empty text token; must not be
	 *                 {@code null}
	 * @param onDone   callback invoked once when the stream is exhausted; receives
	 *                 the total token count; must not be {@code null}
	 * @throws Exception if the HTTP request fails or the provider returns a non-200
	 *                   status
	 * @since v2026.1.0
	 */
	public void callProviderStream(Map<String, Object> provider, String modelId, List<Map<String, Object>> messages,
			Consumer<String> onToken, Consumer<Integer> onDone) throws Exception {
		String type = (String) provider.get("type");
		String apiKey = cryptoService.decryptKey((String) provider.get("api_key_enc"));
		int[] totalTokens = { 0 };

		if ("ollama".equals(type)) {
			String base = ollamaService.cleanBaseUrl((String) provider.get("base_url"));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("messages", messages);
			body.put("stream", true);
			Map<String, Object> options = new LinkedHashMap<>();
			options.put("temperature", 0.5);
			options.put("num_predict", 4096);
			body.put("options", options);

			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + "/api/chat"))
					.timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200)
				throw new RuntimeException("Ollama " + resp.statusCode());

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isBlank())
						continue;
					try {
						JsonNode parsed = mapper.readTree(line);
						String token = parsed.path("message").path("content").asText("");
						if (!token.isEmpty())
							onToken.accept(token);
						if (parsed.has("eval_count"))
							totalTokens[0] = parsed.get("eval_count").asInt(0);
						if (parsed.path("done").asBoolean(false))
							break;
					} catch (Exception ignored) {
					}
				}
			}

		} else if ("anthropic".equals(type)) {
			String base = ollamaService
					.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.anthropic.com"));
			List<Map<String, Object>> anthropicMessages = new ArrayList<>();
			String systemContent = "";
			for (Map<String, Object> m : messages) {
				if ("system".equals(m.get("role")))
					systemContent = (String) m.get("content");
				else
					anthropicMessages.add(m);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("max_tokens", 4096);
			body.put("stream", true);
			body.put("system", systemContent);
			body.put("messages", anthropicMessages);

			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + "/v1/messages"))
					.timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
					.header("anthropic-version", "2023-06-01").header("x-api-key", apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200)
				throw new RuntimeException("Anthropic " + resp.statusCode());

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.startsWith("data: "))
						continue;
					try {
						JsonNode parsed = mapper.readTree(line.substring(6));
						if ("content_block_delta".equals(parsed.path("type").asText())) {
							String token = parsed.path("delta").path("text").asText("");
							if (!token.isEmpty())
								onToken.accept(token);
						}
						if ("message_delta".equals(parsed.path("type").asText())) {
							totalTokens[0] = parsed.path("usage").path("output_tokens").asInt(totalTokens[0]);
						}
					} catch (Exception ignored) {
					}
				}
			}

		} else {
			String base = resolveOpenAIBase(provider, type);
			String url = "groq".equals(type) ? base + "/v1/chat/completions" : base + "/chat/completions";
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", modelId);
			body.put("messages", messages);
			body.put("stream", true);

			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMinutes(5))
					.header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
			HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() != 200)
				throw new RuntimeException("Provider " + resp.statusCode());

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.startsWith("data: "))
						continue;
					String payload = line.substring(6).trim();
					if ("[DONE]".equals(payload))
						break;
					try {
						JsonNode parsed = mapper.readTree(payload);
						String token = parsed.path("choices").path(0).path("delta").path("content").asText("");
						if (!token.isEmpty())
							onToken.accept(token);
						if (parsed.has("usage"))
							totalTokens[0] = parsed.get("usage").path("total_tokens").asInt(totalTokens[0]);
					} catch (Exception ignored) {
					}
				}
			}
		}

		onDone.accept(totalTokens[0]);
	}

	/**
	 * Resolves the OpenAI-compatible base URL from a provider configuration map.
	 *
	 * <p>
	 * Returns the configured {@code base_url} if present and non-blank. Otherwise
	 * falls back to the standard Groq endpoint
	 * ({@code https://api.groq.com/openai}) for {@code type = "groq"} or the
	 * standard OpenAI endpoint ({@code https://api.openai.com/v1}) for all other
	 * types.
	 *
	 * @param provider provider configuration map
	 * @param type     provider type string (e.g. {@code "groq"}, {@code "openai"})
	 * @return cleaned base URL without a trailing slash
	 * @since v2026.1.0
	 */
	private String resolveOpenAIBase(Map<String, Object> provider, String type) {
		String base = (String) provider.get("base_url");
		if (base == null || base.isBlank()) {
			base = "groq".equals(type) ? "https://api.groq.com/openai" : "https://api.openai.com/v1";
		}
		return ollamaService.cleanBaseUrl(base);
	}

	/**
	 * Mirrors an approved API model into the unified {@code models} table.
	 *
	 * <p>
	 * When an API model's {@code is_approved} flag is {@code true} it is upserted
	 * into {@code models} so that the router can select it like any Ollama model.
	 * When {@code is_approved} is {@code false} the corresponding row is deleted.
	 * The composite model ID is formed as {@code <providerId>:<modelId>}.
	 *
	 * @param provider provider configuration map (must contain {@code id} and
	 *                 {@code name})
	 * @param apiModel API model map containing at minimum {@code model_id},
	 *                 {@code is_approved}, and optionally {@code display_name} and
	 *                 {@code context_window}
	 * @since v2026.1.0
	 */
	public void mirrorApiModelToModels(Map<String, Object> provider, Map<String, Object> apiModel) {
		String modelId = provider.get("id") + ":" + apiModel.get("model_id");
		Object isApproved = apiModel.get("is_approved");
		boolean approved = isApproved != null
				&& (isApproved instanceof Boolean ? (Boolean) isApproved : ((Number) isApproved).intValue() != 0);

		if (approved) {
			String name = (String) apiModel.getOrDefault("display_name", apiModel.get("model_id"));
			Object ctxWin = apiModel.get("context_window");
			db.update(
					"INSERT INTO models (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at) "
							+ "VALUES (?, ?, ?, ?, 'available', '[\"general\",\"ask\"]', 70, 80, 'external', ?, ?) "
							+ "ON CONFLICT(id) DO UPDATE SET name=excluded.name, status='available', "
							+ "context_size=COALESCE(excluded.context_size, context_size), last_seen_at=excluded.last_seen_at",
					modelId, name, provider.get("id"), apiModel.get("model_id"),
					ctxWin != null ? ((Number) ctxWin).intValue() : null, java.time.Instant.now().toString());
		} else {
			db.update("DELETE FROM models WHERE id = ?", modelId);
		}
	}
}
