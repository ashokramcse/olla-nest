package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.AgentLoopService;
import com.ollanest.service.DatabaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Agent loop API — starts and cancels agentic chat sessions.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController extends BaseController {

    private final AgentLoopService agentLoopService;
    private final DatabaseService databaseService;

    public AgentController(AgentLoopService agentLoopService, DatabaseService databaseService) {
        this.agentLoopService = agentLoopService;
        this.databaseService = databaseService;
    }

    @PostMapping("/run/{sessionId}")
    public SseEmitter run(HttpServletRequest req, @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);

        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
        String model = (String) body.getOrDefault("model", databaseService.getSetting("defaultModel", "llama3.2"));
        boolean canBash = "admin".equals(user.role) || hasRight(user, "bash:use");

        emitter.onTimeout(() -> {
            agentLoopService.cancel(sessionId);
            try { emitter.complete(); } catch (Exception ignore) {}
        });

        agentLoopService.runLoop(sessionId, user.id, messages, ollamaUrl, model, canBash, emitter);
        return emitter;
    }

    @PostMapping("/cancel/{sessionId}")
    public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String sessionId) {
        requireAuth(req);
        agentLoopService.cancel(sessionId);
        return ok(Map.of("ok", true));
    }

    @GetMapping("/status/{sessionId}")
    public ResponseEntity<?> status(HttpServletRequest req, @PathVariable String sessionId) {
        requireAuth(req);
        return ok(Map.of("running", agentLoopService.isRunning(sessionId)));
    }

    private boolean hasRight(User user, String right) {
        try {
            return user.rights != null && user.rights.contains(right);
        } catch (Exception e) {
            return false;
        }
    }
}
