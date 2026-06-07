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
 * Persistent memory storage and retrieval service for the personal AI memory system.
 *
 * <p>Memories are short text snippets (preferences, facts, decisions, context) that
 * the user or agent wants to retain across sessions. Each memory is owner-scoped,
 * optionally linked to the originating session, tagged for filtering, and optionally
 * backed by a vector embedding for semantic retrieval.
 *
 * <p>Retrieval is dual-mode:
 * <ol>
 * <li>Semantic search via {@link EmbeddingService} cosine similarity (when embeddings
 *     are available)</li>
 * <li>Keyword overlap fallback (always available, no embedding required)</li>
 * </ol>
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Without persistent memory, every conversation starts from scratch. This service gives
 * the assistant a long-term memory that grows with use, enabling personalised responses,
 * continuity between sessions, and user-controlled recall and deletion of stored facts.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A per-user cap of {@value #MAX_MEMORIES_PER_USER} entries is enforced by evicting
 *     the oldest, lowest-importance 10% when the cap is reached.</li>
 * <li>Embeddings are stored as JSON arrays in {@code embedding_json} and are excluded
 *     from the serialised output returned to clients.</li>
 * <li>Cosine similarity uses a 0.25 threshold to filter low-relevance results before
 *     ranking.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with semantic + keyword search, cap enforcement, and
 *     bulk import/export</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    /** Maximum number of memory entries per user; oldest low-importance entries are evicted beyond this. */
    private static final int MAX_MEMORIES_PER_USER = 2000;

    /** JDBC template for memory persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for tags and embedding JSON serialisation. */
    private final ObjectMapper mapper;

    /** Embedding service used to compute vector representations for semantic search. */
    private final EmbeddingService embeddingService;

    /**
     * Constructor-injects persistence, serialisation, and embedding dependencies.
     *
     * @param db               JDBC template for the {@code memories} table
     * @param mapper           shared Jackson mapper
     * @param embeddingService service for computing embedding vectors
     * @since v2026.2.1
     */
    public MemoryService(JdbcTemplate db, ObjectMapper mapper, EmbeddingService embeddingService) {
        this.db = db;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Persist a new memory snippet. Computes and stores an embedding vector for
     * semantic search when {@link EmbeddingService} is available; falls back to
     * keyword search otherwise. Enforces a per-user cap of {@value #MAX_MEMORIES_PER_USER}
     * memories by evicting the oldest lowest-importance entries when the cap is reached.
     *
     * @param owner     the user ID that owns this memory
     * @param text      the memory text to store
     * @param sessionId the chat session that produced this memory (may be {@code null})
     * @param source    origin label: {@code "user"}, {@code "agent"}, or {@code "import"}
     * @param tags      optional classification tags
     * @return the persisted memory record
     * @since v2026.2.1
     */
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

    /**
     * Delete a single memory. Throws {@link NoSuchElementException} if not found
     * or owned by a different user.
     *
     * @param id    the memory ID
     * @param owner the user ID that must own this memory
     * @since v2026.2.1
     */
    public void forget(String id, String owner) {
        int rows = db.update("DELETE FROM memories WHERE id = ? AND owner = ?", id, owner);
        if (rows == 0) throw new NoSuchElementException("Memory not found: " + id);
    }

    /**
     * Deletes all memories for the given owner — used on account wipe or explicit user request.
     *
     * @param owner the user ID whose memories should all be deleted
     * @since v2026.2.1
     */
    public void forgetAll(String owner) {
        db.update("DELETE FROM memories WHERE owner = ?", owner);
    }

    /**
     * List memories for an owner ordered by creation time descending.
     *
     * @param owner the user ID
     * @param limit maximum results; 0 or negative defaults to 100
     * @return memory records (without raw embedding vectors)
     * @since v2026.2.1
     */
    public List<Map<String, Object>> list(String owner, int limit) {
        return db.queryForList(
                "SELECT id, owner, text, source, session_id, tags_json, importance, created_at FROM memories WHERE owner = ? ORDER BY created_at DESC LIMIT ?",
                owner, limit > 0 ? limit : 100)
                .stream().map(this::mapRow).toList();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches memories for the given owner, using semantic search when embeddings are
     * available and falling back to keyword overlap otherwise.
     *
     * @param owner the user ID
     * @param query the search query string
     * @param topK  maximum number of results to return
     * @return ranked list of matching memory records; never null
     * @since v2026.2.1
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

    /**
     * Bulk-import a list of memory texts. Blank entries are silently skipped.
     * Each memory is stored via {@link #remember} so the cap and embedding rules apply.
     *
     * @param owner  the user ID
     * @param texts  the list of memory strings to import
     * @param source origin label attached to every imported memory; defaults to {@code "import"}
     * @return the number of memories successfully stored
     * @since v2026.2.1
     */
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

    /**
     * Returns every memory for the given owner, used for full backup/export.
     *
     * @param owner the user ID
     * @return complete list of memory records; never null
     * @since v2026.2.1
     */
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
