package com.ollanest.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.ModelService;
import com.ollanest.service.OllamaService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin model management endpoints.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code GET /api/admin/ollama/ping} — test connectivity to an Ollama server
 *       and list its available models</li>
 *   <li>{@code PATCH /api/admin/models/{id}/governance} — update governance fields
 *       on any model in the registry (status, tier, GPU flag, etc.)</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>The ping endpoint previously returned HTTP 500 on connection errors; this
 *       was fixed in v2026.1.0 to always return 200 with {@code ok: false} and an
 *       error message so the frontend can display a user-friendly status.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 * @version v2026.1.0  — bug fix: fixed HTTP 500 on /ping when Ollama unreachable
 */
@RestController
@RequestMapping("/api/admin")
public class AdminModelsController extends BaseController {

    /** JDBC template for model and governance DB queries. */
    private final JdbcTemplate db;

    /** Used to parse DB rows into {@link com.ollanest.model.ModelRecord} objects. */
    private final ModelService modelService;

    /** Used to resolve the Ollama base URL and clean URL strings. */
    private final OllamaService ollamaService;

    /** Used to write audit log entries for governance changes. */
    private final ChatService chatService;

    /**
     * Constructor-injects all required dependencies.
     *
     * @param  db            the JDBC template
     * @param  modelService  the model registry service
     * @param  ollamaService the Ollama HTTP client
     * @param  chatService   the audit log helper
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public AdminModelsController(JdbcTemplate db, ModelService modelService,
            OllamaService ollamaService, ChatService chatService) {
        this.db = db;
        this.modelService = modelService;
        this.ollamaService = ollamaService;
        this.chatService = chatService;
    }

    /**
     * Tests connectivity to the Ollama server and returns its model list.
     *
     * <p>Sends a GET to {@code <url>/api/tags}. On success, returns model names
     * and count. On any exception (connection refused, timeout, DNS failure),
     * returns {@code 200 OK} with {@code ok: false} and an error message rather
     * than propagating a 500.
     *
     * @param  url  optional custom Ollama base URL to test; if absent, uses the
     *              configured {@code ollamaUrl} setting
     * @param  req  the current HTTP request (for admin auth check)
     * @return      200 OK with {@code {ok, url, status?, modelCount?, models?}}
     *              or {@code {ok: false, error: ...}} on failure
     * @since   v2026.1.0  — initial Java Spring Boot migration
     * @version v2026.1.0  — bug fix: always returns 200 with ok:false on exception
     */
    @GetMapping("/ollama/ping")
    public ResponseEntity<Map<String, Object>> ollamaPing(
            @RequestParam(required = false) String url, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String testUrl = url != null ? ollamaService.cleanBaseUrl(url) : ollamaService.ollamaUrl();
        try {
            URI uri = URI.create(testUrl + "/api/tags");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            try {
                JsonNode data = new ObjectMapper().readTree(resp.body());
                JsonNode models = data.get("models");
                int modelCount = models != null && models.isArray() ? models.size() : 0;
                List<String> modelNames = new ArrayList<>();
                if (models != null && models.isArray()) {
                    for (JsonNode m : models) modelNames.add(m.path("name").asText(""));
                }
                return ResponseEntity.ok(Map.of(
                        "ok", resp.statusCode() == 200,
                        "url", testUrl,
                        "status", resp.statusCode(),
                        "modelCount", modelCount,
                        "models", modelNames));
            } catch (Exception parseEx) {
                return ResponseEntity.ok(Map.of(
                        "ok", false, "url", testUrl, "error", "Invalid response from Ollama"));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "ok", false,
                    "url", testUrl,
                    "error", e.getMessage() != null ? e.getMessage() : "Connection failed"));
        }
    }

    /**
     * Updates governance fields on a specific model record.
     *
     * <p>Updatable fields: {@code status}, {@code governanceTier}, {@code resourceTier},
     * {@code gpuRequired}, {@code maxConcurrency}, {@code maxContextSize},
     * {@code externalCostTier}, {@code sensitiveAllowed}. Only fields present in the
     * request body are updated; missing keys are skipped.
     *
     * @param  id    the model ID to update
     * @param  body  request body with governance fields (camelCase)
     * @param  req   the current HTTP request (for admin auth check)
     * @return       200 OK with the updated model record, or 404 if not found
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @PatchMapping("/models/{id}/governance")
    public ResponseEntity<Map<String, Object>> updateGovernance(@PathVariable String id,
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> models = db.queryForList(
                "SELECT id FROM models WHERE id = ?", id);
        if (models.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Model not found"));

        Map<String, String> fieldMap = new LinkedHashMap<>();
        fieldMap.put("status", "status");
        fieldMap.put("governanceTier", "governance_tier");
        fieldMap.put("resourceTier", "resource_tier");
        fieldMap.put("gpuRequired", "gpu_required");
        fieldMap.put("maxConcurrency", "max_concurrency");
        fieldMap.put("maxContextSize", "max_context_size");
        fieldMap.put("externalCostTier", "external_cost_tier");
        fieldMap.put("sensitiveAllowed", "sensitive_allowed");

        for (Map.Entry<String, String> e : fieldMap.entrySet()) {
            if (!body.containsKey(e.getKey())) continue;
            Object val;
            if ("gpuRequired".equals(e.getKey()) || "sensitiveAllowed".equals(e.getKey())) {
                val = Boolean.TRUE.equals(body.get(e.getKey())) ? 1 : 0;
            } else {
                val = body.get(e.getKey());
            }
            db.update("UPDATE models SET " + e.getValue() + " = ? WHERE id = ?", val, id);
        }
        chatService.appendAudit(admin.name, "admin.model.governance",
                "Updated governance for " + id, null);
        List<Map<String, Object>> updated = db.queryForList(
                "SELECT * FROM models WHERE id = ?", id);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "model", updated.isEmpty() ? Map.of() : modelService.parseModel(updated.get(0))));
    }
}
