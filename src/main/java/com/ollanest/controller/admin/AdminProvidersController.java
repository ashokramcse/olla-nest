package com.ollanest.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.CryptoService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.ProviderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin/providers")
public class AdminProvidersController extends BaseController {

    private final JdbcTemplate db;
    private final CryptoService cryptoService;
    private final OllamaService ollamaService;
    private final ProviderService providerService;
    private final ChatService chatService;
    private final ObjectMapper mapper;

    public AdminProvidersController(JdbcTemplate db, CryptoService cryptoService, OllamaService ollamaService,
                                    ProviderService providerService, ChatService chatService, ObjectMapper mapper) {
        this.db = db;
        this.cryptoService = cryptoService;
        this.ollamaService = ollamaService;
        this.providerService = providerService;
        this.chatService = chatService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listProviders(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> providers = db.queryForList(
            "SELECT id, name, type, base_url, enabled, created_at, updated_at FROM api_providers ORDER BY name");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> p : providers) {
            Map<String, Object> item = new LinkedHashMap<>(p);
            Object enabled = p.get("enabled");
            item.put("enabled", enabled != null && ((Number) enabled).intValue() != 0);
            Integer mc = db.queryForObject("SELECT COUNT(*) FROM api_models WHERE provider_id = ?", Integer.class, p.get("id"));
            item.put("modelCount", mc != null ? mc : 0);
            result.add(item);
        }
        return ResponseEntity.ok(Map.of("ok", true, "providers", result));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createProvider(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        String name = (String) body.get("name");
        String type = (String) body.get("type");
        String apiKey = (String) body.get("api_key");
        if (name == null || type == null || apiKey == null)
            return ResponseEntity.status(400).body(Map.of("error", "name, type, and api_key are required"));
        String id = uid("prov");
        String now = Instant.now().toString();
        try {
            db.update("INSERT INTO api_providers (id, name, type, base_url, api_key_enc, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                id, name, type, body.get("base_url"), cryptoService.encryptKey(apiKey), now, now);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        chatService.appendAudit(admin.name, "admin.provider.create", "Created provider " + name, null);
        return ResponseEntity.ok(Map.of("ok", true, "provider", Map.of("id", id, "name", name, "type", type, "enabled", true)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateProvider(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM api_providers WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Provider not found"));
        Map<String, Object> existing = rows.get(0);
        String name = body.containsKey("name") ? (String) body.get("name") : (String) existing.get("name");
        String type = body.containsKey("type") ? (String) body.get("type") : (String) existing.get("type");
        Object baseUrl = body.containsKey("base_url") ? body.get("base_url") : existing.get("base_url");
        String keyEnc = body.containsKey("api_key") ? cryptoService.encryptKey((String) body.get("api_key")) : (String) existing.get("api_key_enc");
        int enabled = body.containsKey("enabled") ? (Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0) : ((Number) existing.get("enabled")).intValue();
        db.update("UPDATE api_providers SET name=?, type=?, base_url=?, api_key_enc=?, enabled=?, updated_at=? WHERE id=?",
            name, type, baseUrl, keyEnc, enabled, Instant.now().toString(), id);
        chatService.appendAudit(admin.name, "admin.provider.update", "Updated provider " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteProvider(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> rows = db.queryForList("SELECT id, name FROM api_providers WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Provider not found"));
        db.update("DELETE FROM api_models WHERE provider_id = ?", id);
        db.update("DELETE FROM api_providers WHERE id = ?", id);
        chatService.appendAudit(admin.name, "admin.provider.delete", "Deleted provider " + rows.get(0).get("name"), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testProvider(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM api_providers WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Provider not found"));
        Map<String, Object> provider = rows.get(0);
        List<Map<String, Object>> models = db.queryForList(
            "SELECT model_id FROM api_models WHERE provider_id = ? ORDER BY is_approved DESC, display_name ASC LIMIT 1", id);
        if (models.isEmpty()) return ResponseEntity.ok(Map.of("ok", false, "latency_ms", 0, "error", "No models synced for this provider yet."));
        String modelId = (String) models.get(0).get("model_id");
        long start = System.currentTimeMillis();
        try {
            providerService.callProvider(provider, modelId, List.of(Map.of("role", "user", "content", "Reply with one word: hello")), 15000);
            return ResponseEntity.ok(Map.of("ok", true, "latency_ms", System.currentTimeMillis() - start, "modelTested", modelId));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "latency_ms", System.currentTimeMillis() - start, "error", e.getMessage(), "modelTested", modelId));
        }
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, Object>> syncProvider(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM api_providers WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Provider not found"));
        Map<String, Object> provider = rows.get(0);
        String type = (String) provider.get("type");
        String apiKey = cryptoService.decryptKey((String) provider.get("api_key_enc"));

        try {
            List<Map<String, Object>> models = new ArrayList<>();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            if ("anthropic".equals(type)) {
                String base = ollamaService.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.anthropic.com"));
                HttpRequest hreq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
                    .GET().build();
                HttpResponse<String> resp = client.send(hreq, HttpResponse.BodyHandlers.ofString());
                if (!resp.body().isEmpty()) {
                    JsonNode data = mapper.readTree(resp.body());
                    JsonNode modelList = data.get("data");
                    if (modelList != null && modelList.isArray()) {
                        for (JsonNode m : modelList) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", m.path("id").asText()); item.put("name", m.path("display_name").asText(m.path("id").asText())); item.put("context", null);
                            models.add(item);
                        }
                    }
                }
            } else {
                String base = ollamaService.cleanBaseUrl((String) provider.getOrDefault("base_url", "https://api.openai.com/v1"));
                String url = "groq".equals(type) ? base + "/v1/models" : base + "/models";
                HttpRequest hreq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
                HttpResponse<String> resp = client.send(hreq, HttpResponse.BodyHandlers.ofString());
                if (!resp.body().isEmpty()) {
                    JsonNode data = mapper.readTree(resp.body());
                    JsonNode modelList = data.has("data") ? data.get("data") : data.get("models");
                    if (modelList != null && modelList.isArray()) {
                        for (JsonNode m : modelList) {
                            String mid = m.path("id").asText(m.path("name").asText(""));
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", mid); item.put("name", mid);
                            item.put("context", m.has("context_window") ? m.get("context_window").asInt() : null);
                            models.add(item);
                        }
                    }
                }
            }

            String now = Instant.now().toString();
            for (Map<String, Object> m : models) {
                String rowId = id + ":" + m.get("id");
                List<Map<String, Object>> existing = db.queryForList("SELECT id, is_approved FROM api_models WHERE id = ?", rowId);
                if (existing.isEmpty()) {
                    db.update("INSERT INTO api_models (id, provider_id, model_id, display_name, context_window, is_approved, governance_tag, created_at) VALUES (?, ?, ?, ?, ?, 0, 'approved', ?)",
                        rowId, id, m.get("id"), m.get("name"), m.get("context"), now);
                } else {
                    Object isApproved = existing.get(0).get("is_approved");
                    if (isApproved != null && ((Number) isApproved).intValue() != 0) {
                        Map<String, Object> apiModel = new LinkedHashMap<>();
                        apiModel.put("id", rowId); apiModel.put("model_id", m.get("id"));
                        apiModel.put("display_name", m.get("name")); apiModel.put("context_window", m.get("context")); apiModel.put("is_approved", 1);
                        providerService.mirrorApiModelToModels(provider, apiModel);
                    }
                }
            }
            chatService.appendAudit(admin.name, "admin.provider.sync", "Synced models for provider " + provider.get("name"), null);
            return ResponseEntity.ok(Map.of("ok", true, "synced", models.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/models")
    public ResponseEntity<Map<String, Object>> listModels(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> models = db.queryForList("SELECT * FROM api_models WHERE provider_id = ? ORDER BY display_name", id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : models) {
            Map<String, Object> item = new LinkedHashMap<>(m);
            Object ia = m.get("is_approved");
            item.put("isApproved", ia != null && ((Number) ia).intValue() != 0);
            result.add(item);
        }
        return ResponseEntity.ok(Map.of("ok", true, "models", result));
    }

    @PutMapping("/{id}/models/{modelId}")
    public ResponseEntity<Map<String, Object>> updateModel(@PathVariable String id, @PathVariable String modelId,
                                                            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String rowId = id + ":" + modelId;
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM api_models WHERE id = ?", rowId);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Model not found"));
        if (body.containsKey("governance_tag")) db.update("UPDATE api_models SET governance_tag = ? WHERE id = ?", body.get("governance_tag"), rowId);
        if (body.containsKey("is_approved")) db.update("UPDATE api_models SET is_approved = ? WHERE id = ?", Boolean.TRUE.equals(body.get("is_approved")) ? 1 : 0, rowId);
        if (body.containsKey("display_name")) db.update("UPDATE api_models SET display_name = ? WHERE id = ?", body.get("display_name"), rowId);
        if (body.containsKey("is_approved")) {
            List<Map<String, Object>> prows = db.queryForList("SELECT * FROM api_providers WHERE id = ?", id);
            List<Map<String, Object>> mrows = db.queryForList("SELECT * FROM api_models WHERE id = ?", rowId);
            if (!prows.isEmpty() && !mrows.isEmpty()) {
                providerService.mirrorApiModelToModels(prows.get(0), mrows.get(0));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/models")
    public ResponseEntity<Map<String, Object>> createModel(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String modelId = (String) body.get("model_id");
        String displayName = (String) body.getOrDefault("display_name", modelId);
        String rowId = id + ":" + modelId;
        db.update("INSERT OR IGNORE INTO api_models (id, provider_id, model_id, display_name, is_approved, governance_tag, created_at) VALUES (?, ?, ?, ?, 0, 'approved', ?)",
            rowId, id, modelId, displayName, Instant.now().toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}/models/{modelId}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable String id, @PathVariable String modelId, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String rowId = id + ":" + modelId;
        db.update("DELETE FROM api_models WHERE id = ?", rowId);
        db.update("DELETE FROM models WHERE id = ?", rowId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
            + Long.toString((long)(Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
    }
}
