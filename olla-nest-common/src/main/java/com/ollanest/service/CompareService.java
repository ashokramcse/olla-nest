package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Blind model A/B comparison service.
 *
 * Creates ephemeral chat sessions for two models with the same prompt.
 * Model identities are hidden ("Model A" / "Model B") until the user votes.
 * Results are stored for personal analytics and, for enterprise, team leaderboards.
 */
@Service
public class CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public CompareService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── Create comparison ─────────────────────────────────────────────────────

    public Map<String, Object> create(String owner, Map<String, Object> req) {
        String id = "cmp-" + Long.toString(System.currentTimeMillis(), 36);
        String sessionIdA = "cmp-sess-a-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionIdB = "cmp-sess-b-" + UUID.randomUUID().toString().substring(0, 8);
        boolean isBlind = !Boolean.FALSE.equals(req.get("is_blind"));
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO comparisons (id, owner, prompt, model_a, model_b, endpoint_a, endpoint_b,
                  session_id_a, session_id_b, is_blind, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.get("prompt"),
                req.get("model_a"),
                req.get("model_b"),
                req.get("endpoint_a"),
                req.get("endpoint_b"),
                sessionIdA, sessionIdB,
                isBlind ? 1 : 0,
                now);

        return Map.of(
                "id", id,
                "session_id_a", sessionIdA,
                "session_id_b", sessionIdB,
                "is_blind", isBlind,
                "label_a", isBlind ? "Model A" : req.get("model_a"),
                "label_b", isBlind ? "Model B" : req.get("model_b"),
                "created_at", now
        );
    }

    // ── Vote ──────────────────────────────────────────────────────────────────

    public Map<String, Object> vote(String id, String owner, String winner) {
        Map<String, Object> existing = getById(id, owner);
        if (existing == null) throw new NoSuchElementException("Comparison not found: " + id);
        if (existing.get("winner") != null) throw new IllegalStateException("Already voted on this comparison");

        db.update("UPDATE comparisons SET winner=?, voted_at=? WHERE id=? AND owner=?",
                winner, Instant.now().toString(), id, owner);

        // Return with model names revealed
        return getById(id, owner);
    }

    // ── History ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> list(String owner, int limit) {
        return db.queryForList(
                "SELECT * FROM comparisons WHERE owner=? ORDER BY created_at DESC LIMIT ?",
                owner, limit > 0 ? limit : 20)
                .stream().map(this::mapRow).toList();
    }

    public Map<String, Object> getById(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM comparisons WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    /** Enterprise: aggregate win rates per model for a team. */
    public List<Map<String, Object>> teamLeaderboard(String teamId) {
        // Count wins per model across all team members
        return db.queryForList("""
                SELECT winner as model, COUNT(*) as wins
                FROM comparisons c
                JOIN users u ON c.owner = u.id
                WHERE u.team = ? AND winner IS NOT NULL AND winner != 'tie'
                GROUP BY winner
                ORDER BY wins DESC""", teamId);
    }

    private Map<String, Object> mapRow(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }
}
