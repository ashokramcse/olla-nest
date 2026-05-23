package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.ChatService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD operations for organisational teams.
 *
 * <p>Manages the {@code teams} table and the {@code user_groups} membership
 * join table. Teams are used to group users for reporting and permission
 * management purposes.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/admin/teams} — list all teams with member counts</li>
 *   <li>{@code POST /api/admin/teams} — create a new team</li>
 *   <li>{@code PATCH /api/admin/teams/{id}} — rename a team</li>
 *   <li>{@code DELETE /api/admin/teams/{id}} — delete a team and its memberships</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamsController extends BaseController {

    /** JDBC template for all team and membership queries. */
    private final JdbcTemplate db;

    /** Used to write audit log entries for team lifecycle events. */
    private final ChatService chatService;

    /**
     * Constructor-injects the JDBC template and chat service.
     *
     * @param  db           the JDBC template
     * @param  chatService  the audit log helper
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public AdminTeamsController(JdbcTemplate db, ChatService chatService) {
        this.db = db;
        this.chatService = chatService;
    }

    /**
     * Lists all teams with their member counts.
     *
     * <p>Member counts are derived from the {@code user_groups} table.
     *
     * @param  req  the current HTTP request (for admin auth check)
     * @return      200 OK with {@code {ok: true, teams: [team]}}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTeams(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> teams = db.queryForList(
                "SELECT id, name, created_at FROM teams ORDER BY name");
        for (Map<String, Object> t : teams) {
            Integer mc = db.queryForObject(
                    "SELECT COUNT(*) FROM user_groups WHERE group_id = ?",
                    Integer.class, t.get("id"));
            t.put("memberCount", mc != null ? mc : 0);
        }
        return ResponseEntity.ok(Map.of("ok", true, "teams", teams));
    }

    /**
     * Creates a new team.
     *
     * <p>Returns 400 if {@code name} is blank or already exists.
     *
     * @param  body  request body with {@code name} (required)
     * @param  req   the current HTTP request (for admin auth check)
     * @return       200 OK with the new team object, or 400 on validation error
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "name is required"));
        }
        List<Map<String, Object>> existing = db.queryForList(
                "SELECT id FROM teams WHERE name = ?", name);
        if (!existing.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "Team name already exists"));
        }
        String id = uid("team");
        String now = Instant.now().toString();
        db.update("INSERT INTO teams (id, name, created_at) VALUES (?, ?, ?)", id, name, now);
        chatService.appendAudit(getUser(req).name, "admin.team.create", "Created team " + name, null);
        return ResponseEntity.ok(Map.of("ok", true,
                "team", Map.of("id", id, "name", name, "created_at", now)));
    }

    /**
     * Renames a team.
     *
     * <p>Only the {@code name} field is updated; other fields are ignored.
     * Returns 404 if the team does not exist.
     *
     * @param  id    the team ID to update
     * @param  body  request body with optional {@code name}
     * @param  req   the current HTTP request (for admin auth check)
     * @return       200 OK {@code {ok: true}}, or 404 if not found
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTeam(@PathVariable String id,
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id FROM teams WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        String name = (String) body.get("name");
        if (name != null && !name.isBlank()) {
            db.update("UPDATE teams SET name = ? WHERE id = ?", name, id);
        }
        chatService.appendAudit(getUser(req).name, "admin.team.update", "Updated team " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Deletes a team and removes all of its member associations.
     *
     * @param  id   the team ID to delete
     * @param  req  the current HTTP request (for admin auth check)
     * @return      200 OK {@code {ok: true}}, or 404 if not found
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTeam(@PathVariable String id,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, name FROM teams WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        db.update("DELETE FROM user_groups WHERE group_id = ?", id);
        db.update("DELETE FROM teams WHERE id = ?", id);
        chatService.appendAudit(getUser(req).name, "admin.team.delete",
                "Deleted team " + rows.get(0).get("name"), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Generates a unique ID with the given prefix using timestamp + random base-36.
     *
     * @param  prefix  the ID prefix (e.g. {@code "team"})
     * @return         a unique string ID
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    private String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
                + Long.toString((long) (Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
    }
}
