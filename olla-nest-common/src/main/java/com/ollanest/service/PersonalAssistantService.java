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
 * Personal AI assistant management — per-user assistant with configurable
 * name, personality, model, enabled tools, and daily check-in tasks.
 *
 * Each user gets exactly one assistant (CrewMember), created on demand.
 * Three daily check-in tasks (Morning/Midday/Evening) are seeded automatically.
 * All settings are user-editable.
 *
 * Enterprise: team assistants share personality/model across all team members.
 */
@Service
public class PersonalAssistantService {

    private static final Logger log = LoggerFactory.getLogger(PersonalAssistantService.class);

    private static final Set<String> SYNTHETIC_OWNERS = Set.of("internal-tool", "api", "demo", "system", "");

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TaskSchedulerService taskService;

    public PersonalAssistantService(JdbcTemplate db, ObjectMapper mapper,
            TaskSchedulerService taskService) {
        this.db = db;
        this.mapper = mapper;
        this.taskService = taskService;
    }

    // ── Get or create ─────────────────────────────────────────────────────────

    public Map<String, Object> getOrCreate(String owner) {
        if (owner == null || SYNTHETIC_OWNERS.contains(owner)) {
            throw new IllegalArgumentException("Cannot create assistant for synthetic owner: " + owner);
        }

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM crew_members WHERE owner=?", owner);

        if (!rows.isEmpty()) {
            return enrichWithCheckIns(rows.get(0), owner);
        }

        // Create on demand
        return create(owner);
    }

    private Map<String, Object> create(String owner) {
        String id = "crew-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO crew_members (id, owner, name, avatar, personality, enabled_tools_json,
                  timezone, greeting, allow_autonomous_email, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                "Assistant",
                "🤖",
                "Helpful, concise, and proactive. Knows the user's context and preferences.",
                "[]",
                "UTC",
                "Hello! How can I help you today?",
                0, now, now);

        // Seed daily check-in tasks
        seedCheckIns(owner);

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM crew_members WHERE owner=?", owner);
        return enrichWithCheckIns(rows.get(0), owner);
    }

    private void seedCheckIns(String owner) {
        String[][] checkIns = {
            {"Morning check-in", "09:00", "Good morning! What are your top priorities for today? Let me know if you need help with anything."},
            {"Midday check-in", "13:00", "How's your day going? Any blockers I can help you work through?"},
            {"Evening wrap-up", "17:00", "How did today go? What do you want to make sure gets done before you sign off?"}
        };

        for (String[] ci : checkIns) {
            taskService.create(owner, Map.of(
                    "name", ci[0],
                    "prompt", ci[2],
                    "task_type", "llm",
                    "schedule", "daily",
                    "scheduled_time", ci[1],
                    "trigger_type", "schedule",
                    "status", "active"
            ));
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public Map<String, Object> update(String owner, Map<String, Object> req) {
        Map<String, Object> existing = getOrCreate(owner);
        String now = Instant.now().toString();

        db.update("""
                UPDATE crew_members SET name=?, avatar=?, personality=?, model=?, endpoint_url=?,
                  enabled_tools_json=?, timezone=?, allow_autonomous_email=?, updated_at=?
                WHERE owner=?""",
                req.getOrDefault("name", existing.get("name")),
                req.getOrDefault("avatar", existing.get("avatar")),
                req.getOrDefault("personality", existing.get("personality")),
                req.get("model"),
                req.get("endpoint_url"),
                toJson(req.getOrDefault("enabled_tools", existing.get("enabled_tools"))),
                req.getOrDefault("timezone", existing.get("timezone")),
                Boolean.TRUE.equals(req.get("allow_autonomous_email")) ? 1 : 0,
                now, owner);

        return getOrCreate(owner);
    }

    // ── Check-ins ─────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getCheckIns(String owner) {
        return db.queryForList(
                "SELECT * FROM scheduled_tasks WHERE owner=? AND task_type='llm' ORDER BY scheduled_time ASC",
                owner);
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
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
