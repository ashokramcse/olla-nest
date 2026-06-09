package com.ollanest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Blind model A/B comparison service that allocates side-by-side ephemeral chat
 * sessions and records user preference votes.
 *
 * <p>
 * Model identities are hidden behind neutral labels ("Model A" / "Model B")
 * until the user casts a vote. After voting the actual model names are
 * revealed. Results are persisted for personal analytics and, in enterprise
 * deployments, team leaderboards.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Choosing between competing LLMs is difficult without objective side-by-side
 * comparison. This service lets users run the same prompt against two models
 * simultaneously and select the better response, building an empirical
 * preference dataset over time.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each comparison allocates two distinct session IDs so the chat subsystem
 * can process them independently without any coupling.</li>
 * <li>The {@code is_blind} flag is stored per-comparison to support future
 * non-blind mode (e.g. known-model evaluations for benchmarking).</li>
 * <li>Voting is write-once: a second vote attempt throws
 * {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with the model comparison feature</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class CompareService {

	private static final Logger log = LoggerFactory.getLogger(CompareService.class);

	/** JDBC template for comparison persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for JSON serialisation. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialisation dependencies.
	 *
	 * @param db     JDBC template for the {@code comparisons} table
	 * @param mapper shared Jackson mapper
	 * @since v2026.2.1
	 */
	public CompareService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	// ── Create comparison ─────────────────────────────────────────────────────

	/**
	 * Creates a new blind A/B model comparison, allocating two ephemeral chat
	 * sessions.
	 *
	 * @param owner the user ID
	 * @param req   fields: {@code prompt}, {@code model_a}, {@code model_b},
	 *              {@code endpoint_a}, {@code endpoint_b}, {@code is_blind}
	 *              (default true)
	 * @return map with {@code id}, {@code session_id_a}, {@code session_id_b},
	 *         {@code label_a}, {@code label_b}, and {@code is_blind}
	 * @since v2026.2.1
	 */
	public Map<String, Object> create(String owner, Map<String, Object> req) {
		String id = "cmp-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String sessionIdA = "cmp-sess-a-" + UUID.randomUUID().toString().substring(0, 8);
		String sessionIdB = "cmp-sess-b-" + UUID.randomUUID().toString().substring(0, 8);
		boolean isBlind = !Boolean.FALSE.equals(req.get("is_blind"));
		String now = Instant.now().toString();

		db.update("""
				INSERT INTO comparisons (id, owner, prompt, model_a, model_b, endpoint_a, endpoint_b,
				  session_id_a, session_id_b, is_blind, created_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?)""", id, owner, req.get("prompt"), req.get("model_a"), req.get("model_b"),
				req.get("endpoint_a"), req.get("endpoint_b"), sessionIdA, sessionIdB, isBlind ? 1 : 0, now);

		return Map.of("id", id, "session_id_a", sessionIdA, "session_id_b", sessionIdB, "is_blind", isBlind, "label_a",
				isBlind ? "Model A" : req.get("model_a"), "label_b", isBlind ? "Model B" : req.get("model_b"),
				"created_at", now);
	}

	// ── Vote ──────────────────────────────────────────────────────────────────

	/**
	 * Records the user's vote for a comparison, revealing the model identities.
	 *
	 * @param id     the comparison ID
	 * @param owner  the user ID — must own the comparison
	 * @param winner the winning side: {@code "a"}, {@code "b"}, or {@code "tie"}
	 * @return the updated comparison record with model names revealed
	 * @throws java.util.NoSuchElementException if the comparison is not found
	 * @throws IllegalStateException            if the user has already voted
	 * @since v2026.2.1
	 */
	public Map<String, Object> vote(String id, String owner, String winner) {
		Map<String, Object> existing = getById(id, owner);
		if (existing == null)
			throw new NoSuchElementException("Comparison not found: " + id);
		if (existing.get("winner") != null)
			throw new IllegalStateException("Already voted on this comparison");

		db.update("UPDATE comparisons SET winner=?, voted_at=? WHERE id=? AND owner=?", winner,
				Instant.now().toString(), id, owner);

		// Return with model names revealed
		return getById(id, owner);
	}

	// ── History ───────────────────────────────────────────────────────────────

	/**
	 * Returns past comparisons for the given owner, newest first.
	 *
	 * @param owner the user ID
	 * @param limit maximum results; {@code 0} or negative defaults to 20
	 * @return list of comparison rows; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> list(String owner, int limit) {
		return db.queryForList("SELECT * FROM comparisons WHERE owner=? ORDER BY created_at DESC LIMIT ?", owner,
				limit > 0 ? limit : 20).stream().map(this::mapRow).toList();
	}

	/**
	 * Returns a comparison by ID, restricted to the given owner.
	 *
	 * @param id    the comparison ID
	 * @param owner the user ID
	 * @return the comparison record, or {@code null} if not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> getById(String id, String owner) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM comparisons WHERE id=? AND owner=?", id, owner);
		return rows.isEmpty() ? null : mapRow(rows.get(0));
	}

	/**
	 * Enterprise: returns the aggregate win-rate leaderboard for all models used by
	 * a team.
	 *
	 * @param teamId the team identifier
	 * @return list of {@code {model, wins}} rows ordered by wins descending; never
	 *         null
	 * @since v2026.2.1
	 */
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
