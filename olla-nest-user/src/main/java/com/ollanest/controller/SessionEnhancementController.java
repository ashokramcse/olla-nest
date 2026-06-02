package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.SessionEnhancementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Session history enhancement API — fork, truncate, topic analysis. */
@RestController
@RequestMapping("/api/sessions")
public class SessionEnhancementController extends BaseController {

    private final SessionEnhancementService enhancementService;

    public SessionEnhancementController(SessionEnhancementService enhancementService) {
        this.enhancementService = enhancementService;
    }

    @PostMapping("/{sessionId}/fork")
    public ResponseEntity<?> fork(HttpServletRequest req, @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        int messageIndex = ((Number) body.getOrDefault("message_index", 10)).intValue();
        return created(enhancementService.forkSession(sessionId, user.id, messageIndex));
    }

    @PostMapping("/{sessionId}/truncate")
    public ResponseEntity<?> truncate(HttpServletRequest req, @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String fromMessageId = (String) body.get("from_message_id");
        if (fromMessageId == null) return badRequest("from_message_id is required");
        enhancementService.truncateHistory(sessionId, user.id, fromMessageId);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{sessionId}/analyze-topics")
    public ResponseEntity<?> analyzeTopics(HttpServletRequest req, @PathVariable String sessionId) {
        User user = requireAuth(req);
        enhancementService.analyzeTopicsAsync(sessionId, user.id);
        return ok(Map.of("ok", true, "message", "Topic analysis started"));
    }
}
