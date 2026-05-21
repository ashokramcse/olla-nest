package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Model helpers: parseModel, allowedModels, etc.
 * Mirrors src/models/model.js parseModel and user.js allowedModels.
 */
@Service
public class ModelService {

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final UserService userService;
    private final DatabaseService databaseService;

    public ModelService(JdbcTemplate db, ObjectMapper mapper, UserService userService, DatabaseService databaseService) {
        this.db = db;
        this.mapper = mapper;
        this.userService = userService;
        this.databaseService = databaseService;
    }

    public ModelRecord parseModel(Map<String, Object> row) {
        ModelRecord m = new ModelRecord();
        m.id = str(row, "id");
        m.name = str(row, "name");
        m.provider = str(row, "provider");
        m.model = str(row, "model_ref");
        m.status = str(row, "status");
        m.capabilities = safeJsonList(str(row, "capabilities"));
        m.speedScore = intVal(row, 0, "speed_score");
        m.qualityScore = intVal(row, 0, "quality_score");
        m.privacy = str(row, "privacy");
        m.lastSeenAt = str(row, "last_seen_at");
        m.governanceTier = strOr(row, "approved-local", "governance_tier");
        m.resourceTier = strOr(row, "standard", "resource_tier");
        m.gpuRequired = boolVal(row, "gpu_required");
        m.maxConcurrency = intVal(row, 2, "max_concurrency");
        long ctxSize = longVal(row, 0, "context_size", "max_context_size");
        m.contextSize = ctxSize > 0 ? ctxSize : null;
        m.maxContextSize = ctxSize;
        m.externalCostTier = strOr(row, "local-free", "external_cost_tier");
        Object sa = row.get("sensitive_allowed");
        m.sensitiveAllowed = sa == null || ((Number) sa).intValue() != 0;
        return m;
    }

    public List<ModelRecord> getAllModels() {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM models ORDER BY provider, name");
        List<ModelRecord> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(parseModel(row));
        return result;
    }

    public List<ModelRecord> allowedModels(com.ollanest.model.User user) {
        List<String> ids = new HashSet<>(userService.allowedModelIds(user)).stream().toList();
        boolean allowApi = databaseService.getSettingBool("allowApiModels", false);

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT * FROM models WHERE status = 'available' OR status = 'configured'");
        List<ModelRecord> result = new ArrayList<>();
        Set<String> idSet = new HashSet<>(ids);
        for (Map<String, Object> row : rows) {
            ModelRecord m = parseModel(row);
            if (idSet.contains(m.id) && (!"api".equals(m.provider) || allowApi)) {
                result.add(m);
            }
        }
        return result;
    }

    // --- Helpers ---
    public List<String> safeJsonList(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private String strOr(Map<String, Object> row, String def, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) return v.toString();
        }
        return def;
    }

    private boolean boolVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }

    private int intVal(Map<String, Object> row, int def, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) { try { return ((Number) v).intValue(); } catch (Exception ignored) {} }
        }
        return def;
    }

    private long longVal(Map<String, Object> row, long def, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) { try { return ((Number) v).longValue(); } catch (Exception ignored) {} }
        }
        return def;
    }
}
