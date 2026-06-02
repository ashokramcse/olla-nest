package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Persistent memory storage and retrieval for the personal memory system.
 *
 * Memories are short text snippets the user or agent wants to retain across
 * sessions: preferences, facts, decisions, context. Each memory is owner-scoped,
 * optionally linked to the session it came from, and tagged for filtering.
 *
 * Retrieval uses a combination of:
 *   1. Semantic search via {@link EmbeddingService} cosine similarity (when available)
 *   2. Keyword overlap fallback (always available)
 *
 * The {@link MemoryExtractorService} runs an LLM-in-the-loop pass after N
 * messages to automatically extract and store memorable facts.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int MAX_MEMORIES_PER_USER = 2000;

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;

    public MemoryService(JdbcTemplate db, ObjectMapper mapper, EmbeddingService embeddingService) {
        this.db = db;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Map<String, Object> remember(String owner, String text, String sessionId, String source, List<String> tags) {
        enforceCapLimit(owner);

        String id = "mem-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        String tagsJson = toJson(tags != null ? tags : List.of());
        String now = Instant.now().toString();

        // Compute embedding for semantic search
        String embeddingJson = null;
        try {
            List<Double> vec = embeddingService.embed(text);
            if (vec != null && !vec.isEmpty()) {
                embeddingJson = mapper.writeValueAsString(vec);
            }
        } catch (Exception e) {
            log.debug("[memory] Embedding failed, will use keyword fallback: {}", e.getMessage());
        }

        db.update("""
                INSERT INTO memories (id, owner, text, source, session_id, embedding_json, tags_json, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                id, owner, text, source != null ? source : "user", sessionId, embeddingJson, tagsJson, now, now);

        log.debug("[memory] Stored memory {} for owner {}", id, owner);
        return buildRecord(id, owner, text, source, sessionId, tags, now);
    }

    public void forget(String id, String owner) {
        int rows = db.update("DELETE FROM memories WHERE id = ? AND owner = ?", id, owner);
        if (rows == 0) throw new NoSuchElementException("Memory not found: " + id);
    }

    public void forgetAll(String owner) {
        db.update("DELETE FROM memories WHERE owner = ?", owner);
    }

    public List<Map<String, Object>> list(String owner, int limit) {
        return db.queryForList(
                "SELECT id, owner, text, source, session_id, tags_json, importance, created_at FROM memories WHERE owner = ? ORDER BY created_at DESC LIMIT ?",
                owner, limit > 0 ? limit : 100)
                .stream().map(this::mapRow).toList();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Search memories for the given owner. Uses semantic search when embeddings
     * are available; falls back to keyword overlap.
     */
    public List<Map<String, Object>> recall(String owner, String query, int topK) {
        List<Map<String, Object>> all = db.queryForList(
                "SELECT id, owner, text, source, session_id, tags_json, importance, embedding_json, created_at FROM memories WHERE owner = ? ORDER BY created_at DESC LIMIT 500",
                owner);

        if (all.isEmpty()) return List.of();

        // Try semantic search first
        try {
            List<Double> queryVec = embeddingService.embed(query);
            if (queryVec != null && !queryVec.isEmpty()) {
                return semanticSearch(all, queryVec, topK);
            }
        } catch (Exception e) {
            log.debug("[memory] Semantic search unavailable, using keyword: {}", e.getMessage());
        }

        return keywordSearch(all, query, topK);
    }

    // ── Import / Export ───────────────────────────────────────────────────────

    public int importMemories(String owner, List<String> texts, String source) {
        int count = 0;
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                remember(owner, text.trim(), null, source != null ? source : "import", null);
                count++;
            }
        }
        return count;
    }

    public List<Map<String, Object>> exportAll(String owner) {
        return list(owner, Integer.MAX_VALUE);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> semanticSearch(List<Map<String, Object>> rows,
            List<Double> queryVec, int topK) {
        record Scored(Map<String, Object> row, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String embJson = (String) row.get("embedding_json");
            if (embJson == null) continue;
            try {
                List<Double> vec = mapper.readValue(embJson, new TypeReference<>() {});
                double sim = cosine(queryVec, vec);
                if (sim > 0.25) scored.add(new Scored(mapRow(row), sim));
            } catch (Exception ignore) {}
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(topK).map(Scored::row).toList();
    }

    private List<Map<String, Object>> keywordSearch(List<Map<String, Object>> rows, String query, int topK) {
        String[] terms = query.toLowerCase().split("\\s+");
        record Scored(Map<String, Object> row, int score) {}
        List<Scored> scored = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String text = ((String) row.get("text")).toLowerCase();
            int hits = 0;
            for (String term : terms) {
                if (text.contains(term)) hits++;
            }
            if (hits > 0) scored.add(new Scored(mapRow(row), hits));
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(topK).map(Scored::row).toList();
    }

    private double cosine(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-10 ? 0 : dot / denom;
    }

    private void enforceCapLimit(String owner) {
        int count = db.queryForObject("SELECT COUNT(*) FROM memories WHERE owner = ?", Integer.class, owner);
        if (count >= MAX_MEMORIES_PER_USER) {
            // Evict oldest 10%
            int toEvict = MAX_MEMORIES_PER_USER / 10;
            db.update("""
                    DELETE FROM memories WHERE id IN (
                      SELECT id FROM memories WHERE owner = ? ORDER BY importance ASC, created_at ASC LIMIT ?
                    )""", owner, toEvict);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("owner", row.get("owner"));
        result.put("text", row.get("text"));
        result.put("source", row.get("source"));
        result.put("session_id", row.get("session_id"));
        result.put("importance", row.get("importance"));
        result.put("created_at", row.get("created_at"));
        try {
            String tagsJson = (String) row.get("tags_json");
            result.put("tags", tagsJson != null ? mapper.readValue(tagsJson, List.class) : List.of());
        } catch (Exception e) {
            result.put("tags", List.of());
        }
        return result;
    }

    private Map<String, Object> buildRecord(String id, String owner, String text,
            String source, String sessionId, List<String> tags, String createdAt) {
        return Map.of(
                "id", id, "owner", owner, "text", text,
                "source", source != null ? source : "user",
                "session_id", sessionId != null ? sessionId : "",
                "tags", tags != null ? tags : List.of(),
                "created_at", createdAt
        );
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
