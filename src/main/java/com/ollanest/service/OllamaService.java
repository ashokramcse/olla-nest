package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Ollama service: sync models, ping, inferCapabilities, inferScores.
 * Background @Scheduled sync every 60 seconds.
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private final JdbcTemplate db;
    private final DatabaseService databaseService;
    private final CryptoService cryptoService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Value("${ollama.url:http://localhost:11434}")
    private String defaultOllamaUrl;

    public OllamaService(JdbcTemplate db, DatabaseService databaseService, CryptoService cryptoService, ObjectMapper mapper) {
        this.db = db;
        this.databaseService = databaseService;
        this.cryptoService = cryptoService;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public String ollamaUrl() {
        String url = databaseService.getSetting("ollamaUrl", defaultOllamaUrl);
        return cleanBaseUrl(url);
    }

    public String cleanBaseUrl(String url) {
        if (url == null) return defaultOllamaUrl;
        return url.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void scheduledSync() {
        try {
            syncOllamaModels();
        } catch (Exception e) {
            log.warn("[ollama-sync] Scheduled sync error: {}", e.getMessage());
        }
    }

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
            if (modelRef == null) continue;
            long sizeBytes = item.containsKey("size") ? ((Number) item.get("size")).longValue() : 0;
            int[] scores = inferScores(modelRef, sizeBytes);
            Long contextSize = null;
            try {
                contextSize = fetchOllamaModelInfo(baseUrl, modelRef);
            } catch (Exception ignored) {}

            String modelId = "ollama:" + modelRef;
            String capJson = mapper.valueToTree(inferCapabilities(modelRef)).toString();

            db.update(
                "INSERT INTO models (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at) " +
                "VALUES (?, ?, 'ollama', ?, 'available', ?, ?, ?, 'local', ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name, provider=excluded.provider, model_ref=excluded.model_ref, " +
                "status=excluded.status, capabilities=excluded.capabilities, speed_score=excluded.speed_score, " +
                "quality_score=excluded.quality_score, privacy=excluded.privacy, context_size=excluded.context_size, last_seen_at=excluded.last_seen_at",
                modelId, modelRef, modelRef, capJson, scores[0], scores[1], contextSize, now);

            seenIds.add(modelId);
        }

        if (!seenIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(seenIds.size(), "?"));
            List<Object> params = new ArrayList<>(seenIds);
            db.update("UPDATE models SET status = 'missing' WHERE provider = 'ollama' AND id NOT IN (" + placeholders + ")",
                params.toArray());
        } else {
            db.update("UPDATE models SET status = 'missing' WHERE provider = 'ollama'");
        }

        ensureDefaultAccess();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("models", installed);
        return result;
    }

    private void ensureDefaultAccess() {
        Integer grantCount = db.queryForObject("SELECT COUNT(*) FROM access_grants", Integer.class);
        if (grantCount != null && grantCount > 0) return;
        List<String> ollamaModels = db.queryForList("SELECT id FROM models WHERE provider = 'ollama'", String.class);
        if (ollamaModels.isEmpty()) return;
        for (String modelId : ollamaModels) {
            db.update("INSERT INTO access_grants (id, subject_type, subject_id, model_id, can_use) VALUES (?, 'group', 'group-all', ?, 1)",
                uid("grant"), modelId);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchOllamaModels(String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(cleanBaseUrl(baseUrl) + "/api/tags"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Ollama returned " + resp.statusCode());
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

    public Long fetchOllamaModelInfo(String baseUrl, String modelName) {
        try {
            String body = mapper.writeValueAsString(Map.of("name", modelName));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cleanBaseUrl(baseUrl) + "/api/show"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode data = mapper.readTree(resp.body());
            JsonNode modelinfo = data.get("modelinfo");
            if (modelinfo != null) {
                Iterator<Map.Entry<String, JsonNode>> fields = modelinfo.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getKey().endsWith(".context_length")) {
                        long val = entry.getValue().asLong(0);
                        if (val > 0) return val;
                    }
                }
            }
            // Fallback: parameters string
            JsonNode params = data.get("parameters");
            if (params != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("num_ctx\\s+(\\d+)").matcher(params.asText(""));
                if (m.find()) return Long.parseLong(m.group(1));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

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

    public int[] inferScores(String modelName, long sizeBytes) {
        String text = modelName.toLowerCase();
        double sizeGb = sizeBytes / (1024.0 * 1024.0 * 1024.0);
        int speedScore;
        if (sizeBytes > 0 && sizeGb < 2) speedScore = 95;
        else if (sizeBytes > 0 && sizeGb < 5) speedScore = 78;
        else if (sizeBytes > 0 && sizeGb < 10) speedScore = 58;
        else if (sizeBytes > 0 && sizeGb < 20) speedScore = 38;
        else speedScore = 25;

        int qualityScore = sizeBytes > 0 ? (int) Math.min(95, 45 + Math.round(sizeGb * 3)) : 60;
        if (text.matches(".*?(think|reason|qwen|gemma|llama|mistral|granite).*")) qualityScore += 8;
        if (text.matches(".*?(ocr|med|coder|code).*")) qualityScore += 6;

        return new int[]{
            Math.max(10, Math.min(100, speedScore)),
            Math.max(10, Math.min(100, qualityScore))
        };
    }

    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl() + "/api/tags"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
            + Long.toString((long)(Math.random() * 36 * 36 * 36 * 36 * 36 * 36), 36);
    }
}
