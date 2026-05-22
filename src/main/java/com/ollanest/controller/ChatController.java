package com.ollanest.controller;

import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import com.ollanest.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final JdbcTemplate db;
    private final ChatService chatService;
    private final RouterService routerService;
    private final ProviderService providerService;
    private final ModelService modelService;
    private final WorkspaceService workspaceService;
    private final DatabaseService databaseService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ChatController(JdbcTemplate db, ChatService chatService, RouterService routerService,
                          ProviderService providerService, ModelService modelService,
                          WorkspaceService workspaceService, DatabaseService databaseService,
                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.db = db;
        this.chatService = chatService;
        this.routerService = routerService;
        this.providerService = providerService;
        this.modelService = modelService;
        this.workspaceService = workspaceService;
        this.databaseService = databaseService;
        this.mapper = mapper;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        if (!userHasRight(user, "chat:use"))
            return ResponseEntity.status(403).body(Map.of("error", "Chat access is not enabled for this account"));
        if (!chatService.checkChatRateLimit(user.id, user.apiRateLimitPerMinute))
            return ResponseEntity.status(429).body(Map.of("error", "Rate limit reached."));

        String message = (String) body.get("message");
        String mode = (String) body.getOrDefault("mode", "ask");
        String manualModelId = (String) body.get("manualModelId");

        if (message == null || message.trim().isEmpty())
            return ResponseEntity.status(400).body(Map.of("error", "Message is required"));
        if (message.length() > 16000)
            return ResponseEntity.status(400).body(Map.of("error", "Message exceeds maximum length of 16,000 characters"));

        // Daily token quota
        Integer todayUsage = db.queryForObject(
            "SELECT COALESCE(SUM(tokens_used),0) FROM chat_messages WHERE user_id = ? AND role = 'assistant' AND date(created_at) = date('now')",
            Integer.class, user.id);
        if (todayUsage != null && user.dailyTokenLimit > 0 && todayUsage >= user.dailyTokenLimit)
            return ResponseEntity.status(429).body(Map.of("error", "Daily token limit reached."));

        ModelRecord manualModel = null;
        if (manualModelId != null) {
            List<ModelRecord> allowed = modelService.allowedModels(user);
            manualModel = allowed.stream().filter(m -> m.id.equals(manualModelId)).findFirst().orElse(null);
            if (manualModel != null) {
                RouterService.SensitivityResult sr = routerService.detectSensitiveContent(message);
                if (sr.isSensitive && !"local".equals(manualModel.privacy))
                    return ResponseEntity.status(403).body(Map.of("error", "Message contains sensitive content and cannot be sent to an external model."));
            }
        }

        RouterService.RouteResult route;
        if (manualModel != null) {
            route = new RouterService.RouteResult();
            route.selected = manualModel; route.tags = List.of("manual"); route.candidates = List.of();
            route.reason = "User manually selected an approved model."; route.privacyBlocked = false; route.sensitiveReasons = List.of();
        } else {
            route = routerService.routeModel(user, message, mode);
        }
        if (route.selected == null) return ResponseEntity.status(403).body(Map.of("error", route.reason));

        Map<String, Object> workspace = workspaceService.workspaceForUser(user.id);
        Map<String, Object> chat = chatService.getActiveChat(user.id);
        String content;
        boolean live = true;
        try {
            Map<String, Object> provider = providerService.resolveProvider(route);
            String systemPrompt = chatService.buildSystemPrompt(mode, route, workspace, databaseService.getSetting("projectKnowledge", ""));
            List<String> images = body.get("images") instanceof List ? (List<String>) body.get("images") : List.of();
            List<Map<String, Object>> messages = chatService.buildContextMessages(
                (String) chat.get("id"), systemPrompt, message, route.selected.model, images);
            ProviderService.ProviderResult result = providerService.callProvider(provider, route.selected.model, messages, 300000);
            content = result.content;
        } catch (Exception e) {
            live = false;
            content = "Auto Router selected " + route.selected.name + ", but the model call did not complete.\n\nReason: " + e.getMessage();
        }

        boolean writeApproved = Boolean.TRUE.equals(body.get("writeToWorkspace")) || "full".equals(workspace.get("permissionMode"));
        boolean shouldWriteLocal = live && Boolean.TRUE.equals(workspace.get("localWritesEnabled")) && writeApproved;
        List<Map<String, Object>> artifacts = shouldWriteLocal ? workspaceService.writeLocalArtifacts(workspace, message, mode, content) : List.of();
        List<Map<String, Object>> extractedFiles = live ? extractFiles(content, message) : List.of();
        String chatContent = content;
        if (!artifacts.isEmpty() || !extractedFiles.isEmpty()) {
            chatContent = content.replaceAll("(?s)```[\\s\\S]*?```", "").replaceAll("\n{3,}", "\n\n").trim();
        }

        String now = Instant.now().toString();
        String chatId = (String) chat.get("id");
        String userMsgId = chatService.uid("msg");
        String asstMsgId = chatService.uid("msg");
        try {
            db.update("INSERT INTO chat_messages (id, session_id, user_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userMsgId, chatId, user.id, "user", message, mode, now);
            db.update("INSERT INTO chat_messages (id, session_id, user_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                asstMsgId, chatId, user.id, "assistant", chatContent, route.selected.id, route.selected.name,
                route.reason, live ? 1 : 0, toJson(artifacts), toJson(extractedFiles), now);
        } catch (Exception e) { log.error("[chat] DB error: {}", e.getMessage()); }

        // Auto-title
        List<Map<String, Object>> cs = db.queryForList("SELECT title FROM chat_sessions WHERE id = ?", chatId);
        String title = cs.isEmpty() ? "New Chat" : (String) cs.get(0).get("title");
        if (title == null || "New Chat".equals(title))
            title = message.substring(0, Math.min(45, message.length())).trim() + (message.length() > 45 ? "…" : "");
        db.update("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?", title, now, chatId);

        Map<String, Object> updatedSession = db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", chatId).get(0);
        Map<String, Object> updatedChat = chatService.buildChatObject(updatedSession);
        chatService.appendTrace(user.id, chatId, message, mode, route.selected.id, route.tags, route.candidates, live);
        chatService.appendAudit(user.name, "chat.request", mode.toUpperCase() + " routed to " + route.selected.name, Map.of("live", live));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content); result.put("route", routeToMap(route));
        result.put("model", route.selected); result.put("live", live);
        result.put("artifacts", artifacts); result.put("extractedFiles", extractedFiles);
        result.put("chat", updatedChat);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        SseEmitter emitter = new SseEmitter(-1L);

        User user = getUser(req);
        if (user == null) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Login required\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        if (!isCsrfOk(req)) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Forbidden\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        if (!userHasRight(user, "chat:use")) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Chat access not enabled\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        if (!chatService.checkChatRateLimit(user.id, user.apiRateLimitPerMinute)) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Rate limit reached.\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }

        String message = (String) body.get("message");
        String mode = (String) body.getOrDefault("mode", "ask");
        String manualModelId = (String) body.get("manualModelId");

        if (message == null || message.trim().isEmpty()) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Message is required\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        if (message.length() > 16000) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Message too long\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }

        // Daily token quota check
        Integer todayUsage = db.queryForObject(
            "SELECT COALESCE(SUM(tokens_used),0) FROM chat_messages WHERE user_id = ? AND role = 'assistant' AND date(created_at) = date('now')",
            Integer.class, user.id);
        if (todayUsage != null && user.dailyTokenLimit > 0 && todayUsage >= user.dailyTokenLimit) {
            try { emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Daily token limit reached.\"}")); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }

        // Run streaming in background thread
        final String finalMessage = message;
        final String finalMode = mode;
        Thread.ofVirtual().start(() -> {
            try {
                ModelRecord manualModel = null;
                if (manualModelId != null) {
                    List<ModelRecord> allowed = modelService.allowedModels(user);
                    manualModel = allowed.stream().filter(m -> m.id.equals(manualModelId)).findFirst().orElse(null);
                    if (manualModel != null) {
                        RouterService.SensitivityResult sr = routerService.detectSensitiveContent(finalMessage);
                        if (sr.isSensitive && !"local".equals(manualModel.privacy)) {
                            emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", "Message contains sensitive content and cannot be sent to an external model."))));
                            emitter.complete(); return;
                        }
                    }
                }

                RouterService.RouteResult route;
                if (manualModel != null) {
                    route = new RouterService.RouteResult();
                    route.selected = manualModel; route.tags = List.of("manual"); route.candidates = List.of();
                    route.reason = "User manually selected an approved model."; route.privacyBlocked = false; route.sensitiveReasons = List.of();
                } else {
                    route = routerService.routeModel(user, finalMessage, finalMode);
                }
                if (route.selected == null) {
                    emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", route.reason))));
                    emitter.complete(); return;
                }

                emitter.send(SseEmitter.event().data(toJson(Map.of(
                    "type", "routing", "model", route.selected.name,
                    "provider", route.selected.provider, "reason", route.reason))));

                Map<String, Object> workspace = workspaceService.workspaceForUser(user.id);
                List<String> images = body.get("images") instanceof List ? (List<String>) body.get("images") : List.of();
                Map<String, Object> chatSession = chatService.getActiveChat(user.id);
                String chatId = (String) chatSession.get("id");
                String systemPrompt = chatService.buildSystemPrompt(finalMode, route, workspace, databaseService.getSetting("projectKnowledge", ""));
                List<Map<String, Object>> messages = chatService.buildContextMessages(chatId, systemPrompt, finalMessage, route.selected.model, images);

                StringBuilder fullContent = new StringBuilder();
                int[] tokensUsed = {0};
                boolean[] live = {true};
                long startMs = System.currentTimeMillis();

                try {
                    Map<String, Object> provider = providerService.resolveProvider(route);
                    providerService.callProviderStream(provider, route.selected.model, messages,
                        token -> {
                            fullContent.append(token);
                            try { emitter.send(SseEmitter.event().data(toJson(Map.of("type", "token", "content", token)))); }
                            catch (Exception ignored) {}
                        },
                        total -> tokensUsed[0] = total);
                } catch (Exception e) {
                    live[0] = false;
                    String errMsg = "Auto Router selected " + route.selected.name + ", but the model call did not complete.\n\nReason: " + e.getMessage();
                    fullContent.append(errMsg);
                    emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", e.getMessage()))));
                }

                String cleanedContent = WorkspaceService.cleanModelOutput(fullContent.toString());
                boolean writeApproved = Boolean.TRUE.equals(body.get("writeToWorkspace")) || "full".equals(workspace.get("permissionMode"));
                boolean shouldWriteLocal = live[0] && Boolean.TRUE.equals(workspace.get("localWritesEnabled")) && writeApproved;
                List<Map<String, Object>> artifacts = shouldWriteLocal ? workspaceService.writeLocalArtifacts(workspace, finalMessage, finalMode, cleanedContent) : List.of();
                List<Map<String, Object>> extractedFiles = live[0] ? extractFiles(cleanedContent, finalMessage) : List.of();
                String chatContent = cleanedContent;
                if (!artifacts.isEmpty() || !extractedFiles.isEmpty()) {
                    chatContent = cleanedContent.replaceAll("(?s)```[\\s\\S]*?```", "").replaceAll("\n{3,}", "\n\n").trim();
                }

                String now = Instant.now().toString();
                long latencyMs = System.currentTimeMillis() - startMs;
                String userMsgId = chatService.uid("msg");
                String asstMsgId = chatService.uid("msg");
                try {
                    db.update("INSERT INTO chat_messages (id, session_id, user_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        userMsgId, chatId, user.id, "user", finalMessage, finalMode, now);
                    db.update("INSERT INTO chat_messages (id, session_id, user_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, tokens_used, latency_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        asstMsgId, chatId, user.id, "assistant", chatContent, route.selected.id, route.selected.name,
                        route.reason, live[0] ? 1 : 0, toJson(artifacts), toJson(extractedFiles),
                        tokensUsed[0], latencyMs, now);
                } catch (Exception e) { log.error("[chat/stream] DB persist error: {}", e.getMessage()); }

                // Auto-title
                List<Map<String, Object>> cs = db.queryForList("SELECT title FROM chat_sessions WHERE id = ?", chatId);
                String title = cs.isEmpty() ? "New Chat" : (String) cs.get(0).get("title");
                if (title == null || "New Chat".equals(title))
                    title = finalMessage.substring(0, Math.min(45, finalMessage.length())).trim() + (finalMessage.length() > 45 ? "…" : "");
                db.update("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?", title, now, chatId);

                chatService.appendTrace(user.id, chatId, finalMessage, finalMode, route.selected.id, route.tags, route.candidates, live[0]);
                chatService.appendAudit(user.name, "chat.stream", finalMode.toUpperCase() + " streamed to " + route.selected.name, Map.of("live", live[0]));

                emitter.send(SseEmitter.event().data(toJson(Map.of(
                    "type", "done", "tokensUsed", tokensUsed[0], "latencyMs", latencyMs,
                    "messageId", asstMsgId, "live", live[0], "artifacts", artifacts, "extractedFiles", extractedFiles))));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", e.getMessage()))));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<Map<String, Object>> clearChat(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> existing = db.queryForList(
            "SELECT id FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1", user.id);
        if (!existing.isEmpty()) {
            db.update("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?",
                Instant.now().toString(), existing.get(0).get("id"));
        }
        chatService.getActiveChat(user.id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/chat")
    public ResponseEntity<Map<String, Object>> deleteChat(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        Map<String, Object> session = chatService.getActiveChatSession(user.id);
        if (session != null) {
            String sessionId = (String) session.get("id");
            db.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
            db.update("DELETE FROM chat_sessions WHERE id = ?", sessionId);
        }
        chatService.getActiveChat(user.id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        String messageId = (String) body.get("messageId");
        String sessionId = (String) body.get("sessionId");
        Object ratingObj = body.get("rating");
        if (messageId == null || sessionId == null || ratingObj == null)
            return ResponseEntity.status(400).body(Map.of("error", "messageId, sessionId, and rating are required"));
        int rating = ((Number) ratingObj).intValue();
        if (rating != 1 && rating != -1)
            return ResponseEntity.status(400).body(Map.of("error", "rating must be 1 or -1"));
        // IDOR guard: verify the message belongs to the user's session
        Integer msgCount = db.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE id = ? AND session_id IN (SELECT id FROM chat_sessions WHERE user_id = ?)",
            Integer.class, messageId, user.id);
        if (msgCount == null || msgCount == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Message not found"));
        db.update("INSERT INTO feedback (id, message_id, session_id, user_id, rating, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            chatService.uid("fb"), messageId, sessionId, user.id, rating, body.get("comment"), Instant.now().toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // --- Helpers ---
    private boolean userHasRight(User user, String right) {
        return "admin".equals(user.role) || (user.rights != null && user.rights.contains(right));
    }

    private List<Map<String, Object>> extractFiles(String content, String message) {
        List<WorkspaceService.Artifact> extracted = workspaceService.extractArtifacts(content, message);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkspaceService.Artifact a : extracted) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", a.name); f.put("content", a.content);
            result.add(f);
        }
        return result;
    }

    private Map<String, Object> routeToMap(RouterService.RouteResult route) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selected", route.selected); m.put("tags", route.tags);
        m.put("candidates", route.candidates); m.put("reason", route.reason);
        m.put("privacyBlocked", route.privacyBlocked); m.put("sensitiveReasons", route.sensitiveReasons);
        return m;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }
}
