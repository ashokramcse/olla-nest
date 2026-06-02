package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.SkillsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Admin skills management — approve/reject learned skills, manage team library. */
@RestController
@RequestMapping("/api/admin/skills")
public class AdminSkillsController extends BaseController {

    private final SkillsService skillsService;

    public AdminSkillsController(SkillsService skillsService) {
        this.skillsService = skillsService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(required = false) String status) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(skillsService.list("admin", null, status, 500));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.approve(id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archive(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.archive(id);
        return ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.deleteSkill(id, null); // admin can delete any skill
        return ok(Map.of("ok", true));
    }
}
