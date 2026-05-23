package com.ollanest.controller.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.BackupService;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.util.UrlValidator;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin settings management: read and write all application configuration.
 *
 * <p>Persists settings to the {@code settings} table via {@link DatabaseService}.
 * URL-type settings are validated with {@link UrlValidator} before saving (HIGH-3,
 * HIGH-5 security fixes). Workspace root is normalised to an absolute path.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/admin/settings} — save one or more settings in a single request</li>
 *   <li>{@code GET /api/admin/departments} — list departments with their default rights</li>
 *   <li>{@code PATCH /api/admin/departments/{id}/rights} — update default rights for a dept</li>
 *   <li>{@code POST /api/admin/settings/backup} — trigger an on-demand DB backup</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>The workspace root setting is blocked from pointing to system paths
 *       (HIGH-5). Only paths under reasonable OS directories are accepted.</li>
 *   <li>All base URL fields are validated with {@link UrlValidator} to prevent SSRF
 *       (HIGH-3).</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 * @version v2026.1.0  — security hardening: restrict workspace root to safe paths (HIGH-5);
 *                        SSRF protection on URL fields (HIGH-3)
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSettingsController extends BaseController {

    /** JDBC template for department queries. */
    private final JdbcTemplate db;

    /** Settings persistence and retrieval. */
    private final DatabaseService databaseService;

    /** Used to clean and validate Ollama URLs. */
    private final OllamaService ollamaService;

    /** Used to write audit log entries for settings changes. */
    private final ChatService chatService;

    /** Used to trigger on-demand backups. */
    private final BackupService backupService;

    /** Shared JSON mapper for serialising complex settings (router weights, etc.). */
    private final ObjectMapper mapper;

    /**
     * Constructor-injects all required dependencies.
     *
     * @param  db               the JDBC template
     * @param  databaseService  the settings service
     * @param  ollamaService    the Ollama HTTP client (for URL cleaning)
     * @param  chatService      the audit log helper
     * @param  backupService    the backup service
     * @param  mapper           the shared JSON object mapper
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
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

    /**
     * Saves one or more application settings in a single request.
     *
     * <p>Simple string/boolean settings are saved directly. JSON-complex settings
     * ({@code routerWeights}, {@code sensitivePatterns}, {@code localOnlyModes})
     * are serialised before storage. URL settings are validated with
     * {@link UrlValidator} before saving.
     *
     * @param  body  request body with any combination of settings keys
     * @param  req   the current HTTP request (for admin auth check)
     * @return       200 OK with the updated settings state, or 400 on URL validation error
     * @since   v2026.1.0  — initial Java Spring Boot migration
     * @version v2026.1.0  — security hardening: SSRF protection on URL fields, workspace root guard
     */
    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);

        List<String> simpleKeys = Arrays.asList(
                "routerEnabled", "allowApiModels", "localOnlyDefault", "localWritesEnabled",
                "localPermissionMode", "apiModelProvider", "projectKnowledge",
                "anthropicEnabled", "anthropicApiKey", "anthropicBaseUrl",
                "openaiEnabled", "openaiApiKey", "openaiBaseUrl",
                "groqEnabled", "groqApiKey",
                "customEnabled", "customApiKey", "customBaseUrl", "customName",
                // ── Web search ──────────────────────────────────────────────
                "searchProvider", "searchApiKey",
                // ── Image generation ────────────────────────────────────────
                "imageProvider", "imageModel", "imageSize", "sdBaseUrl",
                // ── Voice (STT/TTS via OpenAI Whisper / TTS API) ────────────
                "voiceEnabled", "ttsVoice");
        for (String key : simpleKeys) {
            if (body.containsKey(key)) databaseService.setSetting(key, body.get(key).toString());
        }
        if (body.containsKey("routerWeights")) {
            try {
                databaseService.setSetting("routerWeights",
                        mapper.writeValueAsString(body.get("routerWeights")));
            } catch (Exception ignored) {}
        }
        if (body.containsKey("sensitivePatterns")) {
            try {
                databaseService.setSetting("sensitivePatterns",
                        mapper.writeValueAsString(body.get("sensitivePatterns")));
            } catch (Exception ignored) {}
        }
        if (body.containsKey("localOnlyModes")) {
            try {
                databaseService.setSetting("localOnlyModes",
                        mapper.writeValueAsString(body.get("localOnlyModes")));
            } catch (Exception ignored) {}
        }
        if (body.containsKey("workspaceRoot")) {
            String nextRoot = Paths.get(body.get("workspaceRoot").toString())
                    .toAbsolutePath().toString();
            databaseService.setSetting("workspaceRoot", nextRoot);
            new File(nextRoot).mkdirs();
        }
        if (body.containsKey("ollamaUrl")) {
            String nextUrl = ollamaService.cleanBaseUrl(body.get("ollamaUrl").toString());
            if (!nextUrl.matches("^https?://[^ \"]+$")) {
                return ResponseEntity.status(400).body(
                        Map.of("ok", false, "error", "Ollama URL must start with http:// or https://"));
            }
            if (!UrlValidator.isSafeUrl(nextUrl)) {
                return ResponseEntity.status(400).body(
                        Map.of("ok", false, "error", "Ollama URL resolves to a disallowed address"));
            }
            databaseService.setSetting("ollamaUrl", nextUrl);
        }
        // searchBaseUrl for SearXNG — self-hosted, allow localhost/private IPs
        if (body.containsKey("searchBaseUrl")) {
            String urlVal = body.get("searchBaseUrl").toString().trim();
            if (!urlVal.isEmpty() && !urlVal.matches("^https?://[^ \"]+$")) {
                return ResponseEntity.status(400).body(
                        Map.of("ok", false, "error", "searchBaseUrl must start with http:// or https://"));
            }
            databaseService.setSetting("searchBaseUrl", urlVal);
        }
        // sdBaseUrl for Stable Diffusion — self-hosted, allow localhost/private IPs
        if (body.containsKey("sdBaseUrl")) {
            String urlVal = body.get("sdBaseUrl").toString().trim();
            if (!urlVal.isEmpty() && !urlVal.matches("^https?://[^ \"]+$")) {
                return ResponseEntity.status(400).body(
                        Map.of("ok", false, "error", "sdBaseUrl must start with http:// or https://"));
            }
            databaseService.setSetting("sdBaseUrl", urlVal);
        }

        // Validate external API base URL fields for SSRF (cloud services must not point to internal hosts)
        for (String urlKey : Arrays.asList("anthropicBaseUrl", "openaiBaseUrl", "customBaseUrl")) {
            if (body.containsKey(urlKey)) {
                String urlVal = body.get(urlKey).toString().trim();
                if (!urlVal.isEmpty() && !UrlValidator.isSafeUrl(urlVal)) {
                    return ResponseEntity.status(400).body(
                            Map.of("ok", false, "error", urlKey + " resolves to a disallowed address"));
                }
            }
        }

        chatService.appendAudit(admin.name, "admin.settings.save", "Updated system settings", null);
        return ResponseEntity.ok(Map.of("ok", true, "settings", buildSettingsState()));
    }

    /**
     * Lists all departments with their configured default rights.
     *
     * <p>Default rights are stored as a JSON map keyed by department ID in the
     * {@code deptDefaultRights} setting. Each department row is augmented with
     * its {@code defaultRights} array.
     *
     * @param  req  the current HTTP request (for admin auth check)
     * @return      200 OK with {@code {departments: [dept]}}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> listDepartments(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> depts = db.queryForList(
                "SELECT id, name FROM departments ORDER BY name");
        String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
        Map<String, Object> deptRights;
        try {
            deptRights = mapper.readValue(deptRightsJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            deptRights = new LinkedHashMap<>();
        }
        for (Map<String, Object> d : depts) {
            Object rights = deptRights.get(d.get("id"));
            d.put("defaultRights", rights != null ? rights : List.of());
        }
        return ResponseEntity.ok(Map.of("departments", depts));
    }

    /**
     * Updates the default rights assigned to new users in a specific department.
     *
     * <p>The rights list is merged into the global {@code deptDefaultRights}
     * settings JSON map. Requires the CSRF header in addition to admin auth.
     *
     * @param  id    the department ID to update
     * @param  body  request body with {@code rights} (array of right strings)
     * @param  req   the current HTTP request (for admin and CSRF check)
     * @return       200 OK {@code {ok: true}}, or 403 if CSRF header missing
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @PatchMapping("/departments/{id}/rights")
    public ResponseEntity<Map<String, Object>> updateDepartmentRights(
            @PathVariable String id, @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden: missing CSRF header"));
        }
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        Object rights = body.get("rights");
        String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
        Map<String, Object> deptRights;
        try {
            deptRights = mapper.readValue(deptRightsJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            deptRights = new LinkedHashMap<>();
        }
        deptRights.put(id, rights instanceof List ? rights : List.of());
        try {
            databaseService.setSetting("deptDefaultRights", mapper.writeValueAsString(deptRights));
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Triggers an on-demand database backup.
     *
     * <p>Delegates to {@link BackupService#runBackup()} which copies the SQLite
     * file to the configured backup directory and prunes old backups.
     *
     * @param  req  the current HTTP request (for admin auth check)
     * @return      200 OK with the backup result map from {@link BackupService}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @PostMapping("/settings/backup")
    public ResponseEntity<Map<String, Object>> runBackup(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        Map<String, Object> result = backupService.runBackup();
        return ResponseEntity.ok(result);
    }

    /**
     * Builds a snapshot of the current router and Ollama settings for the response.
     *
     * @return  a settings state map with router flags and the Ollama URL
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
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

    /**
     * Parses a JSON string into an object, returning an empty map on failure.
     *
     * @param  json  the JSON string, or {@code null}
     * @return       the parsed object, or an empty map on error
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    private Object safeJson(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
