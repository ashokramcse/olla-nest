package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.*;

/**
 * Enterprise admin endpoints — team-scoped resources, analytics, audit trail,
 * connector-aware memory, team onboarding, and the admin analytics dashboard.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Layers enterprise/team features on top of the per-user product: it aggregates
 * usage analytics across all users, manages team-scoped memory and skills
 * (owner key {@code "team:<id>"}), surfaces the audit trail, extracts memory
 * from connector documents, and onboards users into teams. Every endpoint is
 * admin-gated.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each handler short-circuits via {@link BaseController#requireAdmin}, which
 * returns a non-null error response when the caller is not an admin.</li>
 * <li>Team-scoped resources reuse the personal services by passing a synthetic
 * {@code "team:<id>"} owner key rather than a user id.</li>
 * <li>Analytics and audit queries run directly against the database via
 * {@link JdbcTemplate} for flexible aggregation.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/admin/enterprise")
public class AdminEnterpriseController extends BaseController {

    /** Direct database access for analytics, audit, and connector queries. */
    private final JdbcTemplate db;

    /** Service used to store team-scoped and connector-derived memories. */
    private final MemoryService memoryService;

    /** Service used to list team-scoped skills. */
    private final SkillsService skillsService;

    /** Service used to monitor active background jobs. */
    private final BackgroundJobService jobService;

    /**
     * Constructor-injects the database template and supporting services.
     *
     * @param db            the JDBC template for direct queries
     * @param memoryService the service backing team/connector memory
     * @param skillsService the service backing team skills
     * @param jobService    the service backing background job monitoring
     * @since v2026.2.1
     */
    public AdminEnterpriseController(JdbcTemplate db, MemoryService memoryService,
            SkillsService skillsService, BackgroundJobService jobService) {
        this.db = db;
        this.memoryService = memoryService;
        this.skillsService = skillsService;
        this.jobService = jobService;
    }

    // ── Analytics dashboard ───────────────────────────────────────────────────

    /**
     * Returns the admin analytics dashboard over a recent time window.
     *
     * <p>
     * Aggregates message/session/user counts, memory/skill/task totals, the most
     * used models, and per-user activity within the last {@code days} days.
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param days size of the look-back window in days (default 30)
     * @return an OK response with the aggregated statistics, or an admin error
     *         response
     * @since v2026.2.1
     */
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

    /**
     * Lists memories scoped to a team.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param teamId the team whose memories are listed
     * @param limit  maximum number of memories to return (default 100)
     * @return an OK response with the team's memories, or an admin error response
     * @since v2026.2.1
     */
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

    /**
     * Stores a new memory scoped to a team.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param teamId the team to attach the memory to
     * @param body   request payload; {@code text} is required
     * @return a CREATED response with the stored memory, a 400 if {@code text} is
     *         missing, or an admin error response
     * @since v2026.2.1
     */
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

    /**
     * Lists active skills scoped to a team.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param teamId the team whose skills are listed
     * @return an OK response with the team's active skills, or an admin error
     *         response
     * @since v2026.2.1
     */
    @GetMapping("/teams/{teamId}/skills")
    public ResponseEntity<?> teamSkills(HttpServletRequest req, @PathVariable String teamId) {
        var err = requireAdmin(req);
        if (err != null) return err;
        return ok(skillsService.list("team:" + teamId, null, "active", 200));
    }

    // ── Comprehensive audit trail ─────────────────────────────────────────────

    /**
     * Queries the audit trail with optional actor and action filters.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param limit  maximum number of events to return (default 50)
     * @param actor  optional exact-match actor filter
     * @param action optional substring filter on the action name
     * @return an OK response with the matching audit events, or an admin error
     *         response
     * @since v2026.2.1
     */
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

    /**
     * Extracts memory facts from a connector's recently synced documents.
     *
     * <p>
     * Reads up to the 50 most recently synced documents for the connector and
     * stores a short "document available" memory for each, scoped to the target
     * team.
     *
     * @param req         the HTTP request; must resolve to an admin user
     * @param connectorId the connector whose documents are mined
     * @param body        request payload; optional {@code team_id} scopes the
     *                    stored memories (default {@code "default"})
     * @return an OK response with the number of facts stored, or an admin error
     *         response
     * @since v2026.2.1
     */
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

    /**
     * Lists all currently active background jobs across users.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @return an OK response with the active jobs, or an admin error response
     * @since v2026.2.1
     */
    @GetMapping("/jobs")
    public ResponseEntity<?> jobs(HttpServletRequest req) {
        var err = requireAdmin(req);
        if (err != null) return err;
        return ok(jobService.listActive());
    }

    // ── Team SSO onboarding ────────────────────────────────────────────────────

    /**
     * Onboards a user into a team and records an audit event.
     *
     * <p>
     * Assigns the user's {@code team} column and writes a {@code team.onboard}
     * entry to the audit trail.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param teamId the team to assign the user to
     * @param userId the user being onboarded
     * @return an OK response echoing the user and team, or an admin error response
     * @since v2026.2.1
     */
    @PostMapping("/teams/{teamId}/onboard-user/{userId}")
    public ResponseEntity<?> onboardUser(HttpServletRequest req,
            @PathVariable String teamId, @PathVariable String userId) {
        var err = requireAdmin(req);
        if (err != null) return err;

        // Assign user to team, apply team preset, and log
        db.update("UPDATE users SET team=? WHERE id=?", teamId, userId);
        db.update("INSERT INTO audit_events (id, actor, action, detail, created_at) VALUES (?,?,?,?,?)",
                "ae-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6),
                "admin", "team.onboard",
                "User " + userId + " onboarded to team " + teamId,
                Instant.now().toString());

        return ok(Map.of("ok", true, "user_id", userId, "team_id", teamId));
    }
}
