package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.DeepResearchService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Research task management API — list, cancel, view reports. */
@RestController
@RequestMapping("/api/research")
public class ResearchController extends BaseController {

    private final DeepResearchService researchService;

    public ResearchController(DeepResearchService researchService) {
        this.researchService = researchService;
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(researchService.listTasks(user.id));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String id) {
        requireAuth(req);
        researchService.cancel(id);
        return ok(Map.of("ok", true));
    }

    @GetMapping(value = "/tasks/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getReport(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        String html = researchService.getReport(id, user.id);
        if (html == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
