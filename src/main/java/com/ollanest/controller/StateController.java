package com.ollanest.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import com.ollanest.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class StateController extends BaseController {

    private final JdbcTemplate db;
    private final UserService userService;
    private final ModelService modelService;
    private final DatabaseService databaseService;
    private final ChatService chatService;
    private final OllamaService ollamaService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper mapper;

    public StateController(JdbcTemplate db, UserService userService, ModelService modelService,
                           DatabaseService databaseService, ChatService chatService,
                           OllamaService ollamaService, WorkspaceService workspaceService,
                           ObjectMapper mapper) {
        this.db = db;
        this.userService = userService;
        this.modelService = modelService;
        this.databaseService = databaseService;
        this.chatService = chatService;
        this.ollamaService = ollamaService;
        this.workspaceService = workspaceService;
        this.mapper = mapper;
    }

    @GetMapping("/api/state")
    public ResponseEntity<Map<String, Object>> getState(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;

        User user = getUser(req);

        List<Map<String, Object>> allModels = db.queryForList("SELECT * FROM models ORDER BY provider, name");
        List<Object> models = new ArrayList<>();
        for (Map<String, Object> row : allModels) models.add(modelService.parseModel(row));

        List<Object> chats;
        if ("admin".equals(user.role)) {
            List<Map<String, Object>> sessions = db.queryForList(
                "SELECT * FROM chat_sessions WHERE is_active = 1 ORDER BY updated_at DESC");
            chats = new ArrayList<>();
            for (Map<String, Object> s : sessions) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", s.get("id")); c.put("userId", s.get("user_id")); c.put("title", s.get("title"));
                c.put("pinned", boolVal(s, "pinned")); c.put("archived", boolVal(s, "archived"));
                c.put("unread", boolVal(s, "unread")); c.put("isActive", boolVal(s, "is_active"));
                Integer mc = db.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id = ?", Integer.class, s.get("id"));
                c.put("messageCount", mc != null ? mc : 0);
                c.put("updatedAt", s.get("updated_at"));
                chats.add(c);
            }
        } else {
            chatService.getActiveChat(user.id);
            List<Map<String, Object>> sessions = db.queryForList(
                "SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY pinned DESC, updated_at DESC LIMIT 50",
                user.id);
            chats = new ArrayList<>();
            for (Map<String, Object> s : sessions) chats.add(chatService.buildChatObject(s));
        }

        List<Map<String, Object>> audit = new ArrayList<>();
        if ("admin".equals(user.role)) {
            List<Map<String, Object>> auditRows = db.queryForList(
                "SELECT * FROM audit_events ORDER BY created_at DESC LIMIT 20");
            for (Map<String, Object> r : auditRows) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", r.get("id")); a.put("actor", r.get("actor")); a.put("action", r.get("action"));
                a.put("detail", r.get("detail")); a.put("extra", safeJson(str(r, "extra_json")));
                a.put("createdAt", r.get("created_at"));
                audit.add(a);
            }
        }

        Integer reqToday = db.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE role = 'user' AND date(created_at) = date('now')", Integer.class);
        List<Map<String, Object>> latencyRow = db.queryForList(
            "SELECT AVG(latency_ms) as avg FROM chat_messages WHERE role = 'assistant' AND latency_ms IS NOT NULL AND date(created_at) = date('now')");
        Double avgLatency = latencyRow.isEmpty() ? null : (Double) latencyRow.get(0).get("avg");

        long uptimeMs = (long)(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());

        // Departments
        List<Map<String, Object>> depts = db.queryForList("SELECT id, name FROM departments ORDER BY name");
        String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
        Map<String, Object> deptRights;
        try { deptRights = mapper.readValue(deptRightsJson, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { deptRights = new LinkedHashMap<>(); }
        for (Map<String, Object> d : depts) {
            Object rights = deptRights.get(d.get("id"));
            d.put("defaultRights", rights != null ? rights : List.of());
        }

        List<Map<String, Object>> groups = db.queryForList("SELECT id, name FROM groups ORDER BY name");
        List<Map<String, Object>> teams = db.queryForList("SELECT id, name, description FROM teams ORDER BY name");

        Map<String, Object> settings = buildSettingsState();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeUser", user);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("reqToday", reqToday != null ? reqToday : 0);
        stats.put("avgLatency", avgLatency != null ? Math.round(avgLatency) : null);
        stats.put("uptimeMs", uptimeMs);
        result.put("stats", stats);
        result.put("departments", depts);
        result.put("groups", groups);
        result.put("teams", teams);
        result.put("models", models);
        result.put("settings", settings);
        result.put("chats", chats);
        result.put("audit", audit);
        result.put("allowedModelIds", userService.allowedModelIds(user));
        result.put("roles", userService.roleCatalog());
        result.put("permissions", userService.permissionCatalog());
        result.put("effectiveAccess", userService.effectiveAccess(user));
        result.put("workspace", workspaceService.workspaceForUser(user.id));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/ollama/ping")
    public ResponseEntity<Map<String, Object>> ollamaPing(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        boolean ok = ollamaService.ping();
        return ResponseEntity.ok(Map.of("ok", ok));
    }

    @GetMapping("/api/ollama/models")
    public ResponseEntity<Map<String, Object>> ollamaModels(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        try {
            Map<String, Object> result = ollamaService.syncOllamaModels();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage(), "models", List.of()));
        }
    }

    private Map<String, Object> buildSettingsState() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("routerEnabled", databaseService.getSettingBool("routerEnabled", true));
        s.put("allowApiModels", databaseService.getSettingBool("allowApiModels", false));
        s.put("localOnlyDefault", databaseService.getSettingBool("localOnlyDefault", true));
        s.put("localWritesEnabled", databaseService.getSettingBool("localWritesEnabled", true));
        s.put("workspaceRoot", databaseService.getSetting("workspaceRoot", "./data/workspace"));
        s.put("localPermissionMode", databaseService.getSetting("localPermissionMode", "default"));
        s.put("ollamaUrl", ollamaService.ollamaUrl());
        s.put("apiModelProvider", databaseService.getSetting("apiModelProvider", "not-configured"));
        s.put("anthropicEnabled", databaseService.getSettingBool("anthropicEnabled", false));
        s.put("anthropicApiKey", !databaseService.getSetting("anthropicApiKey", "").isBlank() ? "set" : "");
        s.put("anthropicBaseUrl", databaseService.getSetting("anthropicBaseUrl", ""));
        s.put("openaiEnabled", databaseService.getSettingBool("openaiEnabled", false));
        s.put("openaiApiKey", !databaseService.getSetting("openaiApiKey", "").isBlank() ? "set" : "");
        s.put("openaiBaseUrl", databaseService.getSetting("openaiBaseUrl", ""));
        s.put("groqEnabled", databaseService.getSettingBool("groqEnabled", false));
        s.put("groqApiKey", !databaseService.getSetting("groqApiKey", "").isBlank() ? "set" : "");
        s.put("customEnabled", databaseService.getSettingBool("customEnabled", false));
        s.put("customName", databaseService.getSetting("customName", ""));
        s.put("customApiKey", !databaseService.getSetting("customApiKey", "").isBlank() ? "set" : "");
        s.put("customBaseUrl", databaseService.getSetting("customBaseUrl", ""));
        s.put("routerWeights", safeJson(databaseService.getSetting("routerWeights", null)));
        s.put("sensitivePatterns", safeJsonList(databaseService.getSetting("sensitivePatterns", null)));
        s.put("localOnlyModes", safeJsonList(databaseService.getSetting("localOnlyModes", null)));
        s.put("projectKnowledge", databaseService.getSetting("projectKnowledge", ""));
        return s;
    }

    @SuppressWarnings("unchecked")
    private Object safeJson(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return mapper.readValue(json, Object.class);
        } catch (Exception e) { return Map.of(); }
    }

    private List<Object> safeJsonList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return mapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) { return List.of(); }
    }

    private String str(Map<String, Object> row, String key) {
        Object v = row.get(key); return v != null ? v.toString() : null;
    }

    private boolean boolVal(Map<String, Object> s, String key) {
        Object v = s.get(key);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "1".equals(v.toString());
    }
}
