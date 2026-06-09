package com.ollanest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages each user's personal AI assistant — a single {@code crew_member} row
 * with a configurable name, personality, model, enabled tools, and daily
 * check-in tasks.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Every user in Olla Nest can have one personalised AI companion that remembers
 * their preferences, greets them in their chosen style, and runs scheduled
 * check-in tasks throughout the day. This service owns the lifecycle: it
 * creates the assistant on demand (no explicit onboarding step required), seeds
 * the three standard daily check-ins, and exposes update and read operations
 * for the settings UI.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A synthetic-owner guard ({@link #SYNTHETIC_OWNERS}) prevents phantom
 * records being created for internal system callers such as {@code "api"} or
 * {@code "system"}.</li>
 * <li>The three daily check-in tasks are created via
 * {@link TaskSchedulerService} so they participate in the normal task-run
 * pipeline and appear in the scheduler UI.</li>
 * <li>{@code enabled_tools_json} is a JSON array stored in the DB and
 * deserialized on every read; callers always receive a typed {@code List}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal productivity
 * expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class PersonalAssistantService {

	private static final Logger log = LoggerFactory.getLogger(PersonalAssistantService.class);

	/** Owner values that must never trigger assistant creation. */
	private static final Set<String> SYNTHETIC_OWNERS = Set.of("internal-tool", "api", "demo", "system", "");

	/** JDBC template for crew_member and scheduled_task persistence. */
	private final JdbcTemplate db;

	/**
	 * Shared Jackson mapper for serializing and deserializing the enabled-tools
	 * JSON array.
	 */
	private final ObjectMapper mapper;

	/** Used to create the seeded daily check-in tasks. */
	private final TaskSchedulerService taskService;

	/**
	 * Constructor-injects persistence, serialization, and scheduling dependencies.
	 *
	 * @param db          the JDBC template
	 * @param mapper      the shared Jackson object mapper
	 * @param taskService the task scheduler service used to seed daily check-ins
	 * @since v2026.2.1
	 */
	public PersonalAssistantService(JdbcTemplate db, ObjectMapper mapper, TaskSchedulerService taskService) {
		this.db = db;
		this.mapper = mapper;
		this.taskService = taskService;
	}

	// ── Get or create ─────────────────────────────────────────────────────────

	/**
	 * Returns the personal assistant for the given owner, creating it on demand if
	 * it does not exist.
	 *
	 * <p>
	 * New assistants are seeded with three daily check-in tasks
	 * (Morning/Midday/Evening). Throws {@link IllegalArgumentException} for
	 * synthetic/reserved owner values.
	 *
	 * @param owner the user ID
	 * @return the assistant record enriched with enabled_tools and check_ins
	 * @throws IllegalArgumentException if owner is null or a reserved synthetic
	 *                                  value
	 * @since v2026.2.1
	 */
	public Map<String, Object> getOrCreate(String owner) {
		if (owner == null || SYNTHETIC_OWNERS.contains(owner)) {
			throw new IllegalArgumentException("Cannot create assistant for synthetic owner: " + owner);
		}

		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM crew_members WHERE owner=?", owner);

		if (!rows.isEmpty()) {
			return enrichWithCheckIns(rows.get(0), owner);
		}

		// Create on demand
		return create(owner);
	}

	private Map<String, Object> create(String owner) {
		String id = "crew-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();

		db.update("""
				INSERT INTO crew_members (id, owner, name, avatar, personality, enabled_tools_json,
				  timezone, greeting, allow_autonomous_email, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?)""", id, owner, "Assistant", "🤖",
				"Helpful, concise, and proactive. Knows the user's context and preferences.", "[]", "UTC",
				"Hello! How can I help you today?", 0, now, now);

		// Seed daily check-in tasks
		seedCheckIns(owner);

		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM crew_members WHERE owner=?", owner);
		return enrichWithCheckIns(rows.get(0), owner);
	}

	private void seedCheckIns(String owner) {
		String[][] checkIns = { { "Morning check-in", "09:00",
				"Good morning! What are your top priorities for today? Let me know if you need help with anything." },
				{ "Midday check-in", "13:00", "How's your day going? Any blockers I can help you work through?" },
				{ "Evening wrap-up", "17:00",
						"How did today go? What do you want to make sure gets done before you sign off?" } };

		for (String[] ci : checkIns) {
			taskService.create(owner, Map.of("name", ci[0], "prompt", ci[2], "task_type", "llm", "schedule", "daily",
					"scheduled_time", ci[1], "trigger_type", "schedule", "status", "active"));
		}
	}

	// ── Update ────────────────────────────────────────────────────────────────

	/**
	 * Updates the personal assistant settings for the given owner.
	 *
	 * @param owner the user ID
	 * @param req   fields to update: {@code name}, {@code avatar},
	 *              {@code personality}, {@code model}, {@code endpoint_url},
	 *              {@code enabled_tools}, {@code timezone},
	 *              {@code allow_autonomous_email}
	 * @return the updated assistant record
	 * @since v2026.2.1
	 */
	public Map<String, Object> update(String owner, Map<String, Object> req) {
		Map<String, Object> existing = getOrCreate(owner);
		String now = Instant.now().toString();

		db.update("""
				UPDATE crew_members SET name=?, avatar=?, personality=?, model=?, endpoint_url=?,
				  enabled_tools_json=?, timezone=?, allow_autonomous_email=?, updated_at=?
				WHERE owner=?""", req.getOrDefault("name", existing.get("name")),
				req.getOrDefault("avatar", existing.get("avatar")),
				req.getOrDefault("personality", existing.get("personality")), req.get("model"), req.get("endpoint_url"),
				toJson(req.getOrDefault("enabled_tools", existing.get("enabled_tools"))),
				req.getOrDefault("timezone", existing.get("timezone")),
				Boolean.TRUE.equals(req.get("allow_autonomous_email")) ? 1 : 0, now, owner);

		return getOrCreate(owner);
	}

	// ── Check-ins ─────────────────────────────────────────────────────────────

	/**
	 * Returns the daily check-in scheduled tasks for the given user, ordered by
	 * scheduled time.
	 *
	 * @param owner the user ID
	 * @return list of check-in task row maps; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> getCheckIns(String owner) {
		return db.queryForList(
				"SELECT * FROM scheduled_tasks WHERE owner=? AND task_type='llm' ORDER BY scheduled_time ASC", owner);
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> enrichWithCheckIns(Map<String, Object> row, String owner) {
		Map<String, Object> result = new LinkedHashMap<>(row);
		try {
			String toolsJson = (String) row.get("enabled_tools_json");
			result.put("enabled_tools", toolsJson != null ? mapper.readValue(toolsJson, List.class) : List.of());
			result.remove("enabled_tools_json");
		} catch (Exception e) {
			result.put("enabled_tools", List.of());
		}
		result.put("check_ins", getCheckIns(owner));
		return result;
	}

	private String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "[]";
		}
	}
}
