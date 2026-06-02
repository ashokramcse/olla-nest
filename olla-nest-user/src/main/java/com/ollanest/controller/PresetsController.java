package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.PresetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Presets / user templates API. */
@RestController
@RequestMapping("/api/presets")
public class PresetsController extends BaseController {

    private final PresetService presetService;

    public PresetsController(PresetService presetService) {
        this.presetService = presetService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(presetService.listAll(user.id));
    }

    @PostMapping("/templates")
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(presetService.createTemplate(user.id, body));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(presetService.updateTemplate(id, user.id, body));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        presetService.deleteTemplate(id, user.id);
        return ok(Map.of("ok", true));
    }
}
