package com.ollanest.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.BackupService;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminSettingsController extends BaseController {

    private final JdbcTemplate db;
    private final DatabaseService databaseService;
    private final OllamaService ollamaService;
    private final ChatService chatService;
    private final BackupService backupService;
    private final ObjectMapper mapper;

    public AdminSettingsController(JdbcTemplate db, DatabaseService databaseService,
                                   OllamaService ollamaService, ChatService chatService,
                                   BackupService backupService, ObjectMapper mapper) {
        this.db = db;
        this.databaseService = databaseService;
        this.ollamaService = ollamaService;
        this.chatService = chatService;
        this.backupService = backupService;
        this.mapper = mapper;
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);

        List<String> simpleKeys = Arrays.asList(
            "routerEnabled", "allowApiModels", "localOnlyDefault", "localWritesEnabled", "localPermissionMode",
            "apiModelProvider", "projectKnowledge",
            "anthropicEnabled", "anthropicApiKey", "anthropicBaseUrl",
            "openaiEnabled", "openaiApiKey", "openaiBaseUrl",
            "groqEnabled", "groqApiKey",
            "customEnabled", "customApiKey", "customBaseUrl", "customName"
        );
        for (String key : simpleKeys) {
            if (body.containsKey(key)) databaseService.setSetting(key, body.get(key).toString());
        }
        if (body.containsKey("routerWeights")) {
            try { databaseService.setSetting("routerWeights", mapper.writeValueAsString(body.get("routerWeights"))); } catch (Exception ignored) {}
        }
        if (body.containsKey("sensitivePatterns")) {
            try { databaseService.setSetting("sensitivePatterns", mapper.writeValueAsString(body.get("sensitivePatterns"))); } catch (Exception ignored) {}
        }
        if (body.containsKey("localOnlyModes")) {
            try { databaseService.setSetting("localOnlyModes", mapper.writeValueAsString(body.get("localOnlyModes"))); } catch (Exception ignored) {}
        }
        if (body.containsKey("workspaceRoot")) {
            String nextRoot = Paths.get(body.get("workspaceRoot").toString()).toAbsolutePath().toString();
            databaseService.setSetting("workspaceRoot", nextRoot);
            new File(nextRoot).mkdirs();
        }
        if (body.containsKey("ollamaUrl")) {
            String nextUrl = ollamaService.cleanBaseUrl(body.get("ollamaUrl").toString());
            if (!nextUrl.matches("^https?://[^ \"]+$"))
                return ResponseEntity.status(400).body(Map.of("error", "Ollama URL must start with http:// or https://"));
            databaseService.setSetting("ollamaUrl", nextUrl);
        }

        chatService.appendAudit(admin.name, "admin.settings.save", "Updated system settings", null);
        return ResponseEntity.ok(Map.of("ok", true, "settings", buildSettingsState()));
    }

    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> listDepartments(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> depts = db.queryForList("SELECT id, name FROM departments ORDER BY name");
        String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
        Map<String, Object> deptRights;
        try { deptRights = mapper.readValue(deptRightsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { deptRights = new LinkedHashMap<>(); }
        for (Map<String, Object> d : depts) {
            Object rights = deptRights.get(d.get("id"));
            d.put("defaultRights", rights != null ? rights : List.of());
        }
        return ResponseEntity.ok(Map.of("departments", depts));
    }

    @PatchMapping("/departments/{id}/rights")
    public ResponseEntity<Map<String, Object>> updateDepartmentRights(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (req.getHeader("x-requested-with") == null) return ResponseEntity.status(403).body(Map.of("error", "Forbidden: missing CSRF header"));
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        Object rights = body.get("rights");
        String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
        Map<String, Object> deptRights;
        try { deptRights = mapper.readValue(deptRightsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { deptRights = new LinkedHashMap<>(); }
        deptRights.put(id, rights instanceof List ? rights : List.of());
        try { databaseService.setSetting("deptDefaultRights", mapper.writeValueAsString(deptRights)); } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/settings/backup")
    public ResponseEntity<Map<String, Object>> runBackup(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        Map<String, Object> result = backupService.runBackup();
        return ResponseEntity.ok(result);
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
        s.put("routerWeights", safeJson(databaseService.getSetting("routerWeights", null)));
        return s;
    }

    private Object safeJson(String json) {
        try { if (json == null || json.isBlank()) return Map.of(); return mapper.readValue(json, Object.class); }
        catch (Exception e) { return Map.of(); }
    }
}
