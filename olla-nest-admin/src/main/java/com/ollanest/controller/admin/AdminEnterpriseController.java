package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;

/**
 * Enterprise admin endpoints — team-scoped resources, analytics, audit trail,
 * connector-aware memory, team onboarding, and admin analytics dashboard.
 *
 * All endpoints require admin role. Enterprise features layer on top of
 * personal features: email/calendar/tasks/memory scoped per-team.
 */
@RestController
@RequestMapping("/api/admin/enterprise")
public class AdminEnterpriseController extends BaseController {

    private final JdbcTemplate db;
    private final MemoryService memoryService;
    private final SkillsService skillsService;
    private final BackgroundJobService jobService;

    public AdminEnterpriseController(JdbcTemplate db, MemoryService memoryService,
            SkillsService skillsService, BackgroundJobService jobService) {
        this.db = db;
        this.memoryService = memoryService;
        this.skillsService = skillsService;
        this.jobService = jobService;
    }

    // ── Analytics dashboard ───────────────────────────────────────────────────

    @GetMapping("/analytics")
    public ResponseEntity<?> analytics(HttpServletRequest req,
            @RequestParam(defaultValue = "30") int days) {
        var err = requireAdmin(req);
        if (err != null) return err;

        String since = Instant.now().minusSeconds(days * 86400L).toString();
        Map<String, Object> stats = new LinkedHashMap<>();

        // Messages per day
        stats.put("total_messages", db.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE created_at > ?", Long.class, since));
        stats.put("total_sessions", db.queryForObject(
                "SELECT COUNT(*) FROM chat_sessions WHERE created_at > ?", Long.class, since));
        stats.put("active_users", db.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM chat_messages WHERE created_at > ?", Long.class, since));
        stats.put("total_memories", db.queryForObject("SELECT COUNT(*) FROM memories", Long.class));
        stats.put("total_skills", db.queryForObject("SELECT COUNT(*) FROM skills WHERE status='active'", Long.class));
        stats.put("active_tasks", db.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE status='active'", Long.class));

        // Top models used
        stats.put("top_models", db.queryForList("""
                SELECT model_name, COUNT(*) as usage_count FROM chat_messages
                WHERE created_at > ? AND model_name IS NOT NULL
                GROUP BY model_name ORDER BY usage_count DESC LIMIT 10""", since));

        // Per-user stats
        stats.put("per_user", db.queryForList("""
                SELECT u.name, u.email, COUNT(m.id) as messages, MAX(m.created_at) as last_active
                FROM users u LEFT JOIN chat_messages m ON m.user_id=u.id AND m.created_at > ?
                GROUP BY u.id ORDER BY messages DESC LIMIT 20""", since));

        return ok(stats);
    }

    // ── Team memory management ────────────────────────────────────────────────

    @GetMapping("/teams/{teamId}/memory")
    public ResponseEntity<?> teamMemory(HttpServletRequest req, @PathVariable String teamId,
            @RequestParam(defaultValue = "100") int limit) {
        var err = requireAdmin(req);
        if (err != null) return err;
        // Return memories tagged with this team
        var memories = db.queryForList(
                "SELECT * FROM memories WHERE owner=? ORDER BY created_at DESC LIMIT ?",
                "team:" + teamId, limit);
        return ok(memories);
    }

    @PostMapping("/teams/{teamId}/memory")
    public ResponseEntity<?> addTeamMemory(HttpServletRequest req, @PathVariable String teamId,
            @RequestBody Map<String, Object> body) {
        var err = requireAdmin(req);
        if (err != null) return err;
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) return badRequest("text is required");
        var memory = memoryService.remember("team:" + teamId, text, null, "admin", null);
        return created(memory);
    }

    // ── Team skills management ────────────────────────────────────────────────

    @GetMapping("/teams/{teamId}/skills")
    public ResponseEntity<?> teamSkills(HttpServletRequest req, @PathVariable String teamId) {
        var err = requireAdmin(req);
        if (err != null) return err;
        return ok(skillsService.list("team:" + teamId, null, "active", 200));
    }

    // ── Comprehensive audit trail ─────────────────────────────────────────────

    @GetMapping("/audit")
    public ResponseEntity<?> audit(HttpServletRequest req,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action) {
        var err = requireAdmin(req);
        if (err != null) return err;

        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (actor != null) { sql.append(" AND actor=?"); args.add(actor); }
        if (action != null) { sql.append(" AND action LIKE ?"); args.add("%" + action + "%"); }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);

        return ok(db.queryForList(sql.toString(), args.toArray()));
    }

    // ── Connector-aware memory extraction ─────────────────────────────────────

    @PostMapping("/connectors/{connectorId}/extract-memory")
    public ResponseEntity<?> extractConnectorMemory(HttpServletRequest req,
            @PathVariable String connectorId,
            @RequestBody Map<String, Object> body) {
        var err = requireAdmin(req);
        if (err != null) return err;

        // Get recent connector documents and extract key facts
        String teamId = (String) body.getOrDefault("team_id", "default");
        var docs = db.queryForList(
                "SELECT title, url FROM connector_documents WHERE connector_id=? ORDER BY synced_at DESC LIMIT 50",
                connectorId);

        int stored = 0;
        for (var doc : docs) {
            String title = (String) doc.get("title");
            if (title != null && !title.isBlank()) {
                memoryService.remember("team:" + teamId,
                        "Document available: " + title + (doc.get("url") != null ? " — " + doc.get("url") : ""),
                        null, "connector", List.of("connector", connectorId));
                stored++;
            }
        }

        return ok(Map.of("ok", true, "facts_stored", stored));
    }

    // ── Background job monitoring ──────────────────────────────────────────────

    @GetMapping("/jobs")
    public ResponseEntity<?> jobs(HttpServletRequest req) {
        var err = requireAdmin(req);
        if (err != null) return err;
        return ok(jobService.listActive());
    }

    // ── Team SSO onboarding ────────────────────────────────────────────────────

    @PostMapping("/teams/{teamId}/onboard-user/{userId}")
    public ResponseEntity<?> onboardUser(HttpServletRequest req,
            @PathVariable String teamId, @PathVariable String userId) {
        var err = requireAdmin(req);
        if (err != null) return err;

        // Assign user to team, apply team preset, and log
        db.update("UPDATE users SET team=? WHERE id=?", teamId, userId);
        db.update("INSERT INTO audit_events (id, actor, action, detail, created_at) VALUES (?,?,?,?,?)",
                "ae-" + Long.toString(System.currentTimeMillis(), 36),
                "admin", "team.onboard",
                "User " + userId + " onboarded to team " + teamId,
                Instant.now().toString());

        return ok(Map.of("ok", true, "user_id", userId, "team_id", teamId));
    }
}
