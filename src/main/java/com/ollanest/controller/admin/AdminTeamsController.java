package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamsController extends BaseController {

    private final JdbcTemplate db;
    private final ChatService chatService;

    public AdminTeamsController(JdbcTemplate db, ChatService chatService) {
        this.db = db;
        this.chatService = chatService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTeams(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> teams = db.queryForList("SELECT id, name, created_at FROM teams ORDER BY name");
        for (Map<String, Object> t : teams) {
            Integer mc = db.queryForObject("SELECT COUNT(*) FROM user_groups WHERE group_id = ?", Integer.class, t.get("id"));
            t.put("memberCount", mc != null ? mc : 0);
        }
        return ResponseEntity.ok(Map.of("ok", true, "teams", teams));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTeam(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String name = (String) body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "name is required"));
        List<Map<String, Object>> existing = db.queryForList("SELECT id FROM teams WHERE name = ?", name);
        if (!existing.isEmpty())
            return ResponseEntity.status(400).body(Map.of("error", "Team name already exists"));
        String id = uid("team");
        String now = Instant.now().toString();
        db.update("INSERT INTO teams (id, name, created_at) VALUES (?, ?, ?)", id, name, now);
        chatService.appendAudit(getUser(req).name, "admin.team.create", "Created team " + name, null);
        return ResponseEntity.ok(Map.of("ok", true, "team", Map.of("id", id, "name", name, "created_at", now)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTeam(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList("SELECT id FROM teams WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        String name = (String) body.get("name");
        if (name != null && !name.isBlank()) {
            db.update("UPDATE teams SET name = ? WHERE id = ?", name, id);
        }
        chatService.appendAudit(getUser(req).name, "admin.team.update", "Updated team " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTeam(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList("SELECT id, name FROM teams WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Team not found"));
        db.update("DELETE FROM user_groups WHERE group_id = ?", id);
        db.update("DELETE FROM teams WHERE id = ?", id);
        chatService.appendAudit(getUser(req).name, "admin.team.delete", "Deleted team " + rows.get(0).get("name"), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
            + Long.toString((long)(Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
    }
}
