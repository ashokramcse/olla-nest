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
 * Provider call layer: callProvider, callProviderStream, resolveProvider.
 * Mirrors src/services/providers.js logic.
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);
    private final JdbcTemplate db;
    private final CryptoService cryptoService;
    private final OllamaService ollamaService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public ProviderService(JdbcTemplate db, CryptoService cryptoService, OllamaService ollamaService, ObjectMapper mapper) {
        this.db = db;
        this.cryptoService = cryptoService;
        this.ollamaService = ollamaService;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public static class ProviderResult {
        public String content;
        public int tokensUsed;
        public String providerName;
        public ProviderResult(String content, int tokensUsed, String providerName) {
            this.content = content; this.tokensUsed = tokensUsed; this.providerName = providerName;
        }
    }

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
        if (rows.isEmpty()) throw new RuntimeException("Provider '" + selected.provider + "' is not configured. Add it in Admin → Providers.");
        Map<String, Object> p = rows.get(0);
        Object enabled = p.get("enabled");
        boolean isEnabled = enabled != null && ((Number) enabled).intValue() != 0;
        if (!isEnabled) throw new RuntimeException("Provider '" + p.get("name") + "' is disabled. Enable it in Admin → Providers.");
        return p;
    }

    public ProviderResult callProvider(Map<String, Object> provider, String modelId, List<Map<String, Object>> messages, int timeoutMs) throws Exception {
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
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                RuntimeException ex = new RuntimeException("Ollama " + resp.statusCode());
                throw ex;
            }
            JsonNode data = mapper.readTree(resp.body());
            String content = "";
            if (data.has("message") && data.get("message").has("content"))
                content = data.get("message").get("content").asText("");
            else if (data.has("response"))
                content = data.get("response").asText("");
            int tokens = data.has("eval_count") ? data.get("eval_count").asInt(0) : 0;
            return new ProviderResult(WorkspaceService.cleanModelOutput(content), tokens, (String) provider.getOrDefault("name", "Ollama"));

        } else if ("anthropic".equals(type)) {
            String base = ollamaService.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.anthropic.com"));
            List<Map<String, Object>> anthropicMessages = new ArrayList<>();
            String systemContent = "";
            for (Map<String, Object> m : messages) {
                if ("system".equals(m.get("role"))) systemContent = (String) m.get("content");
                else anthropicMessages.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId); body.put("max_tokens", 4096);
            body.put("system", systemContent); body.put("messages", anthropicMessages);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/messages"))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new RuntimeException("Anthropic " + resp.statusCode());
            JsonNode data = mapper.readTree(resp.body());
            String content = data.has("content") && data.get("content").isArray() && data.get("content").size() > 0
                ? data.get("content").get(0).get("text").asText("") : "";
            int tokens = 0;
            if (data.has("usage")) {
                tokens = data.get("usage").path("input_tokens").asInt(0) + data.get("usage").path("output_tokens").asInt(0);
            }
            return new ProviderResult(content, tokens, "Anthropic");

        } else {
            // openai / groq / custom
            String base = resolveOpenAIBase(provider, type);
            String url = "groq".equals(type) ? base + "/v1/chat/completions" : base + "/chat/completions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId); body.put("messages", messages); body.put("stream", false);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : 300000))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new RuntimeException("Provider " + resp.statusCode());
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

    public void callProviderStream(Map<String, Object> provider, String modelId, List<Map<String, Object>> messages,
                                   Consumer<String> onToken, Consumer<Integer> onDone) throws Exception {
        String type = (String) provider.get("type");
        String apiKey = cryptoService.decryptKey((String) provider.get("api_key_enc"));
        int[] totalTokens = {0};

        if ("ollama".equals(type)) {
            String base = ollamaService.cleanBaseUrl((String) provider.get("base_url"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId); body.put("messages", messages); body.put("stream", true);
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("temperature", 0.5); options.put("num_predict", 4096);
            body.put("options", options);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new RuntimeException("Ollama " + resp.statusCode());

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode parsed = mapper.readTree(line);
                        String token = parsed.path("message").path("content").asText("");
                        if (!token.isEmpty()) onToken.accept(token);
                        if (parsed.has("eval_count")) totalTokens[0] = parsed.get("eval_count").asInt(0);
                        if (parsed.path("done").asBoolean(false)) break;
                    } catch (Exception ignored) {}
                }
            }

        } else if ("anthropic".equals(type)) {
            String base = ollamaService.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.anthropic.com"));
            List<Map<String, Object>> anthropicMessages = new ArrayList<>();
            String systemContent = "";
            for (Map<String, Object> m : messages) {
                if ("system".equals(m.get("role"))) systemContent = (String) m.get("content");
                else anthropicMessages.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId); body.put("max_tokens", 4096); body.put("stream", true);
            body.put("system", systemContent); body.put("messages", anthropicMessages);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/messages"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new RuntimeException("Anthropic " + resp.statusCode());

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    try {
                        JsonNode parsed = mapper.readTree(line.substring(6));
                        if ("content_block_delta".equals(parsed.path("type").asText())) {
                            String token = parsed.path("delta").path("text").asText("");
                            if (!token.isEmpty()) onToken.accept(token);
                        }
                        if ("message_delta".equals(parsed.path("type").asText())) {
                            totalTokens[0] = parsed.path("usage").path("output_tokens").asInt(totalTokens[0]);
                        }
                    } catch (Exception ignored) {}
                }
            }

        } else {
            String base = resolveOpenAIBase(provider, type);
            String url = "groq".equals(type) ? base + "/v1/chat/completions" : base + "/chat/completions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId); body.put("messages", messages); body.put("stream", true);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new RuntimeException("Provider " + resp.statusCode());

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String payload = line.substring(6).trim();
                    if ("[DONE]".equals(payload)) break;
                    try {
                        JsonNode parsed = mapper.readTree(payload);
                        String token = parsed.path("choices").path(0).path("delta").path("content").asText("");
                        if (!token.isEmpty()) onToken.accept(token);
                        if (parsed.has("usage")) totalTokens[0] = parsed.get("usage").path("total_tokens").asInt(totalTokens[0]);
                    } catch (Exception ignored) {}
                }
            }
        }

        onDone.accept(totalTokens[0]);
    }

    private String resolveOpenAIBase(Map<String, Object> provider, String type) {
        String base = (String) provider.get("base_url");
        if (base == null || base.isBlank()) {
            base = "groq".equals(type) ? "https://api.groq.com/openai" : "https://api.openai.com/v1";
        }
        return ollamaService.cleanBaseUrl(base);
    }

    public void mirrorApiModelToModels(Map<String, Object> provider, Map<String, Object> apiModel) {
        String modelId = provider.get("id") + ":" + apiModel.get("model_id");
        Object isApproved = apiModel.get("is_approved");
        boolean approved = isApproved != null && (isApproved instanceof Boolean ? (Boolean) isApproved : ((Number) isApproved).intValue() != 0);

        if (approved) {
            String name = (String) apiModel.getOrDefault("display_name", apiModel.get("model_id"));
            Object ctxWin = apiModel.get("context_window");
            db.update(
                "INSERT INTO models (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at) " +
                "VALUES (?, ?, ?, ?, 'available', '[\"general\",\"ask\"]', 70, 80, 'external', ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name, status='available', " +
                "context_size=COALESCE(excluded.context_size, context_size), last_seen_at=excluded.last_seen_at",
                modelId, name, provider.get("id"), apiModel.get("model_id"),
                ctxWin != null ? ((Number) ctxWin).intValue() : null,
                java.time.Instant.now().toString());
        } else {
            db.update("DELETE FROM models WHERE id = ?", modelId);
        }
    }
}
