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
 * Manages persistent, reusable skill definitions that augment the agent loop's
 * capabilities at inference time.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Skills are structured knowledge snippets in a SKILL.md-inspired format.
 * Rather than relying solely on the base model's training, users and the agent
 * itself can author skills — capturing step-by-step procedures, trigger
 * conditions, common pitfalls, and verification checks. The agent loop calls
 * {@link #search} to retrieve relevant skills for the current task and injects
 * them into the system prompt, effectively giving the model domain-specific
 * procedural memory.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Skills authored by users ({@code source="user"}) are immediately
 * {@code active}; skills extracted by the agent ({@code source="learned"}) start
 * as {@code draft} pending admin approval to prevent prompt-injection via learned
 * content.</li>
 * <li>Team-shared skills ({@code owner IS NULL}) are visible to all users;
 * per-user skills are private to their owner.</li>
 * <li>All JSON array fields ({@code tags}, {@code procedure}, etc.) are stored
 * as JSON columns and deserialized on every read.</li>
 * <li>{@link #search} uses a lightweight keyword-hit scoring approach rather
 * than vector search to avoid embedding overhead for the common case of small
 * skill libraries.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the agent skill-augmentation expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class SkillsService {

    private static final Logger log = LoggerFactory.getLogger(SkillsService.class);

    /** Maximum number of agent-learned skills per owner before LRU eviction. */
    private static final int MAX_LEARNED_SKILLS = 500;

    /** JDBC template for all skill persistence operations. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for serializing and deserializing skill JSON array fields. */
    private final ObjectMapper mapper;

    /** Embedding service (reserved for future semantic search upgrade). */
    private final EmbeddingService embeddingService;

    /**
     * Constructor-injects persistence, serialization, and embedding dependencies.
     *
     * @param db               the JDBC template for skill CRUD operations
     * @param mapper           the shared Jackson object mapper
     * @param embeddingService the embedding service (reserved for semantic search)
     * @since v2026.2.1
     */
    public SkillsService(JdbcTemplate db, ObjectMapper mapper, EmbeddingService embeddingService) {
        this.db = db;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Create a new skill. User-authored skills ({@code source="user"}) start as {@code active};
     * agent-learned skills ({@code source="learned"}) start as {@code draft} pending admin approval.
     *
     * @param req    skill fields (name, description, category, when_to_use, procedure, pitfalls, verification, tags, etc.)
     * @param owner  the user ID that owns this skill, or {@code null} for team-shared skills
     * @return the persisted skill record
     * @since v2026.2.1
     */
    public Map<String, Object> createSkill(Map<String, Object> req, String owner) {
        String id = "skill-" + Long.toString(System.currentTimeMillis(), 36);
        String name = getString(req, "name", "Untitled Skill");
        String description = getString(req, "description", "");
        String category = getString(req, "category", "general");
        String whenToUse = getString(req, "when_to_use", "");
        String source = getString(req, "source", "user");
        String status = "user".equals(source) ? "active" : "draft";
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO skills (id, name, description, category, tags_json, platforms_json,
                  when_to_use, procedure_json, pitfalls_json, verification_json,
                  status, confidence, source, owner, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, name, description, category,
                toJson(req.getOrDefault("tags", List.of())),
                toJson(req.getOrDefault("platforms", List.of())),
                whenToUse,
                toJson(req.getOrDefault("procedure", List.of())),
                toJson(req.getOrDefault("pitfalls", List.of())),
                toJson(req.getOrDefault("verification", List.of())),
                status,
                req.getOrDefault("confidence", 0.8),
                source, owner,
                getString(req, "version", "1.0.0"),
                now, now);

        return getById(id, owner);
    }

    /**
     * Partially update a skill. Only fields present in {@code req} are changed.
     * Operates on skills owned by {@code owner} or team-shared skills (owner IS NULL).
     * Throws {@link NoSuchElementException} if the skill is not found.
     *
     * @param id     the skill ID
     * @param req    fields to update
     * @param owner  the requesting user ID
     * @return the updated skill record
     * @since v2026.2.1
     */
    public Map<String, Object> updateSkill(String id, Map<String, Object> req, String owner) {
        Map<String, Object> existing = getById(id, owner);
        if (existing == null) throw new NoSuchElementException("Skill not found: " + id);

        String now = Instant.now().toString();
        db.update("""
                UPDATE skills SET name=?, description=?, category=?, tags_json=?, platforms_json=?,
                  when_to_use=?, procedure_json=?, pitfalls_json=?, verification_json=?,
                  status=?, confidence=?, updated_at=?
                WHERE id=? AND (owner=? OR owner IS NULL)""",
                getString(req, "name", (String) existing.get("name")),
                getString(req, "description", (String) existing.get("description")),
                getString(req, "category", (String) existing.get("category")),
                toJson(req.getOrDefault("tags", existing.get("tags"))),
                toJson(req.getOrDefault("platforms", existing.get("platforms"))),
                getString(req, "when_to_use", (String) existing.get("when_to_use")),
                toJson(req.getOrDefault("procedure", existing.get("procedure"))),
                toJson(req.getOrDefault("pitfalls", existing.get("pitfalls"))),
                toJson(req.getOrDefault("verification", existing.get("verification"))),
                getString(req, "status", (String) existing.get("status")),
                req.getOrDefault("confidence", existing.get("confidence")),
                now, id, owner);

        return getById(id, owner);
    }

    /**
     * Delete a skill owned by {@code owner} or a team-shared skill. Throws
     * {@link NoSuchElementException} if not found.
     *
     * @param id    the skill ID
     * @param owner the requesting user ID
     * @since v2026.2.1
     */
    public void deleteSkill(String id, String owner) {
        int rows = db.update("DELETE FROM skills WHERE id = ? AND (owner = ? OR owner IS NULL)", id, owner);
        if (rows == 0) throw new NoSuchElementException("Skill not found or not owned by you: " + id);
    }

    /**
     * Fetch a single skill visible to {@code owner} (own skills + team-shared skills).
     *
     * @param id    the skill ID
     * @param owner the requesting user ID
     * @return the skill record, or {@code null} if not found
     * @since v2026.2.1
     */
    public Map<String, Object> getById(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM skills WHERE id = ? AND (owner = ? OR owner IS NULL)", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    /**
     * List skills visible to {@code owner}, ordered by source (user first), confidence, then updated.
     *
     * @param owner    the requesting user ID
     * @param category optional category filter; {@code null} returns all categories
     * @param status   optional status filter ({@code active}, {@code draft}, {@code archived}); {@code null} returns all
     * @param limit    maximum results; 0 or negative defaults to 100
     * @return matching skills; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> list(String owner, String category, String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM skills WHERE (owner = ? OR owner IS NULL)");
        List<Object> args = new ArrayList<>();
        args.add(owner);

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY source DESC, confidence DESC, updated_at DESC LIMIT ?");
        args.add(limit > 0 ? limit : 100);

        return db.queryForList(sql.toString(), args.toArray()).stream().map(this::mapRow).toList();
    }

    /**
     * Keyword-based skill search across name, description, when_to_use, and category.
     * Scores each skill by term-hit count and returns the top-K results.
     * Only {@code active} skills are searched.
     *
     * @param owner  the requesting user ID
     * @param query  space-separated search terms
     * @param topK   maximum number of results to return
     * @return matching skills sorted by relevance score descending; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> search(String owner, String query, int topK) {
        List<Map<String, Object>> all = list(owner, null, "active", 1000);
        if (all.isEmpty()) return List.of();

        String q = query.toLowerCase();
        record Scored(Map<String, Object> row, int score) {}
        List<Scored> scored = new ArrayList<>();

        for (Map<String, Object> skill : all) {
            int hits = 0;
            String[] fields = {
                (String) skill.get("name"),
                (String) skill.get("description"),
                (String) skill.get("when_to_use"),
                (String) skill.get("category")
            };
            for (String[] terms = q.split("\\s+"); ; ) {
                for (String term : terms) {
                    for (String field : fields) {
                        if (field != null && field.toLowerCase().contains(term)) hits++;
                    }
                }
                break;
            }
            if (hits > 0) scored.add(new Scored(skill, hits));
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(topK).map(Scored::row).toList();
    }

    /**
     * Approves a learned skill, changing its status from {@code draft} to {@code active}.
     * Admin action.
     *
     * @param id the skill ID to approve
     * @since v2026.2.1
     */
    public void approve(String id) {
        db.update("UPDATE skills SET status='active', updated_at=? WHERE id=?", Instant.now().toString(), id);
    }

    /**
     * Archives (soft-rejects) a skill, changing its status to {@code archived}.
     * Admin action.
     *
     * @param id the skill ID to archive
     * @since v2026.2.1
     */
    public void archive(String id) {
        db.update("UPDATE skills SET status='archived', updated_at=? WHERE id=?", Instant.now().toString(), id);
    }

    /**
     * Increments the {@code use_count} for a skill. Called by the agent loop each
     * time a skill is retrieved and injected into a conversation.
     *
     * @param id the skill ID
     * @since v2026.2.1
     */
    public void recordUse(String id) {
        db.update("UPDATE skills SET use_count = use_count + 1 WHERE id = ?", id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        for (String field : List.of("tags_json", "platforms_json", "procedure_json", "pitfalls_json", "verification_json")) {
            try {
                String json = (String) row.get(field);
                String key = field.replace("_json", "");
                r.put(key, json != null ? mapper.readValue(json, List.class) : List.of());
                r.remove(field);
            } catch (Exception e) {
                r.put(field.replace("_json", ""), List.of());
            }
        }
        return r;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }

    private String getString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    private void enforceLearnedCap(String owner) {
        int count = db.queryForObject(
                "SELECT COUNT(*) FROM skills WHERE owner = ? AND source = 'learned'", Integer.class, owner);
        if (count >= MAX_LEARNED_SKILLS) {
            db.update("""
                    DELETE FROM skills WHERE id IN (
                      SELECT id FROM skills WHERE owner = ? AND source = 'learned'
                      ORDER BY use_count ASC, updated_at ASC LIMIT ?
                    )""", owner, 50);
        }
    }
}
