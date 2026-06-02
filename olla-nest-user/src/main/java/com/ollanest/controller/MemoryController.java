package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Memory API — CRUD, semantic search, import/export.
 * All endpoints are owner-scoped to the authenticated user.
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController extends BaseController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "100") int limit) {
        User user = requireAuth(req);
        return ok(memoryService.list(user.id, limit));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest req,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int top_k) {
        User user = requireAuth(req);
        return ok(memoryService.recall(user.id, q, top_k));
    }

    @PostMapping
    public ResponseEntity<?> remember(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) {
            return badRequest("text is required");
        }
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.getOrDefault("tags", List.of());
        String sessionId = (String) body.get("session_id");
        var memory = memoryService.remember(user.id, text.trim(), sessionId, "user", tags);
        return created(memory);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> forget(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        memoryService.forget(id, user.id);
        return ok(Map.of("ok", true));
    }

    @DeleteMapping
    public ResponseEntity<?> forgetAll(HttpServletRequest req) {
        User user = requireAuth(req);
        memoryService.forgetAll(user.id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/import")
    public ResponseEntity<?> importMemories(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) body.getOrDefault("memories", List.of());
        String source = (String) body.getOrDefault("source", "import");
        int count = memoryService.importMemories(user.id, texts, source);
        return ok(Map.of("ok", true, "imported", count));
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(HttpServletRequest req) {
        User user = requireAuth(req);
        List<Map<String, Object>> memories = memoryService.exportAll(user.id);
        return ok(Map.of("memories", memories, "count", memories.size()));
    }
}
