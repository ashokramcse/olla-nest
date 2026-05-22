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
import java.util.*;

@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String DEFAULT_EMBED_MODEL = "nomic-embed-text";

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    @Value("${ollama.url:http://localhost:11434}")
    private String ollamaUrlProp;

    public EmbeddingService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    private String ollamaUrl() {
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = 'ollamaUrl'");
            if (!rows.isEmpty() && rows.get(0).get("value") != null) {
                String v = rows.get(0).get("value").toString().trim();
                if (!v.isEmpty()) return v.replaceAll("/+$", "");
            }
        } catch (Exception ignored) {}
        return ollamaUrlProp.replaceAll("/+$", "");
    }

    /** Returns embedding vector for text, or empty list on failure */
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            String model = embedModel();
            Map<String, Object> reqBody = Map.of("model", model, "input", text);
            String json = mapper.writeValueAsString(reqBody);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl() + "/api/embed"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode embeddings = root.get("embeddings");
            if (embeddings != null && embeddings.isArray() && embeddings.size() > 0) {
                JsonNode vec = embeddings.get(0);
                List<Double> result = new ArrayList<>();
                for (JsonNode v : vec) result.add(v.asDouble());
                return result;
            }
        } catch (Exception e) {
            log.warn("[embed] Embedding failed: {}", e.getMessage());
        }
        return List.of();
    }

    private String embedModel() {
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = 'embeddingModel'");
            if (!rows.isEmpty() && rows.get(0).get("value") != null) {
                String v = rows.get(0).get("value").toString().trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        return DEFAULT_EMBED_MODEL;
    }

    /** Cosine similarity between two vectors, returns 0 if either is empty */
    public double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.isEmpty() || b.isEmpty() || a.size() != b.size()) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    /** Keyword-based fallback similarity (BM25-like, no embedding required) */
    public double keywordSimilarity(String query, String chunk) {
        if (query == null || chunk == null) return 0.0;
        Set<String> queryTerms = tokenize(query.toLowerCase());
        Set<String> chunkTerms = tokenize(chunk.toLowerCase());
        long hits = queryTerms.stream().filter(chunkTerms::contains).count();
        return queryTerms.isEmpty() ? 0.0 : (double) hits / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        for (String word : text.split("[^a-z0-9]+")) {
            if (word.length() > 2) terms.add(word);
        }
        return terms;
    }

    public String vectorToJson(List<Double> vec) {
        try { return mapper.writeValueAsString(vec); } catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    public List<Double> jsonToVector(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
            return (List<Double>) mapper.readValue(json, List.class).stream()
                .map(v -> ((Number) v).doubleValue()).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) { return List.of(); }
    }
}
