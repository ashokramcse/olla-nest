package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.SkillsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Skills API — CRUD and search for agent skills.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillsController extends BaseController {

    private final SkillsService skillsService;

    public SkillsController(SkillsService skillsService) {
        this.skillsService = skillsService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(defaultValue = "100") int limit) {
        User user = requireAuth(req);
        return ok(skillsService.list(user.id, category, status, limit));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest req,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int top_k) {
        User user = requireAuth(req);
        return ok(skillsService.search(user.id, q, top_k));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var skill = skillsService.getById(id, user.id);
        if (skill == null) return notFound("Skill not found");
        return ok(skill);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(skillsService.createSkill(body, user.id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(skillsService.updateSkill(id, body, user.id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        skillsService.deleteSkill(id, user.id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/use")
    public ResponseEntity<?> recordUse(HttpServletRequest req, @PathVariable String id) {
        requireAuth(req);
        skillsService.recordUse(id);
        return ok(Map.of("ok", true));
    }
}
