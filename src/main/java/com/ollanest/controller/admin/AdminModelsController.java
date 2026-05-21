package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.ModelService;
import com.ollanest.service.OllamaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminModelsController extends BaseController {

    private final JdbcTemplate db;
    private final ModelService modelService;
    private final OllamaService ollamaService;
    private final ChatService chatService;

    public AdminModelsController(JdbcTemplate db, ModelService modelService, OllamaService ollamaService, ChatService chatService) {
        this.db = db;
        this.modelService = modelService;
        this.ollamaService = ollamaService;
        this.chatService = chatService;
    }

    @GetMapping("/ollama/ping")
    public ResponseEntity<Map<String, Object>> ollamaPing(@RequestParam(required = false) String url, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String testUrl = url != null ? ollamaService.cleanBaseUrl(url) : ollamaService.ollamaUrl();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode models = data.get("models");
            int modelCount = models != null && models.isArray() ? models.size() : 0;
            List<String> modelNames = new ArrayList<>();
            if (models != null && models.isArray()) for (com.fasterxml.jackson.databind.JsonNode m : models) modelNames.add(m.path("name").asText(""));
            return ResponseEntity.ok(Map.of("ok", resp.statusCode() == 200, "url", testUrl,
                "status", resp.statusCode(), "modelCount", modelCount, "models", modelNames));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "url", testUrl, "error", e.getMessage()));
        }
    }

    @PatchMapping("/models/{id}/governance")
    public ResponseEntity<Map<String, Object>> updateGovernance(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> models = db.queryForList("SELECT id FROM models WHERE id = ?", id);
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
        chatService.appendAudit(admin.name, "admin.model.governance", "Updated governance for " + id, null);
        List<Map<String, Object>> updated = db.queryForList("SELECT * FROM models WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("ok", true, "model", updated.isEmpty() ? Map.of() : modelService.parseModel(updated.get(0))));
    }
}
