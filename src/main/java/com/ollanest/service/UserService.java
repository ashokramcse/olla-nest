package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * User model helpers: publicUser, allowedModels, effectiveAccess, etc.
 * Mirrors src/models/user.js logic.
 */
@Service
public class UserService {

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public UserService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public User publicUser(Map<String, Object> row) {
        if (row == null) return null;
        User u = new User();
        u.id = str(row, "id");
        u.name = str(row, "name");
        u.email = str(row, "email");
        u.role = str(row, "role");
        u.rights = safeJsonList(str(row, "rights"));
        u.departmentId = firstStr(row, "department_id", "departmentId");
        u.active = boolVal(row, "active");
        u.employeeId = strOr(row, "", "employee_id", "employeeId");
        u.designation = strOr(row, "", "designation");
        u.team = strOr(row, "", "team");
        u.branch = strOr(row, "", "branch");
        u.manager = strOr(row, "", "manager");
        u.organization = strOr(row, "Olla Nest", "organization");
        u.aiAccessTier = strOr(row, "standard", "ai_access_tier", "aiAccessTier");
        u.dailyTokenLimit = longVal(row, 50000, "daily_token_limit", "dailyTokenLimit");
        u.monthlyTokenLimit = longVal(row, 1000000, "monthly_token_limit", "monthlyTokenLimit");
        u.gpuQuotaMinutes = longVal(row, 120, "gpu_quota_minutes", "gpuQuotaMinutes");
        u.vramLimitMb = longVal(row, 8192, "vram_limit_mb", "vramLimitMb");
        u.concurrentModelLimit = longVal(row, 1, "concurrent_model_limit", "concurrentModelLimit");
        u.apiRateLimitPerMinute = longVal(row, 30, "api_rate_limit_per_minute", "apiRateLimitPerMinute");
        u.maxContextSize = longVal(row, 8192, "max_context_size", "maxContextSize");
        u.mfaEnabled = boolVal(row, "mfa_enabled", "mfaEnabled");
        u.securityRiskScore = longVal(row, 10, "security_risk_score", "securityRiskScore");
        u.accessStatus = strOr(row, u.active ? "active" : "suspended", "access_status", "accessStatus");
        u.accessExpiresAt = strOr(row, "", "access_expires_at", "accessExpiresAt");
        u.lastActiveAt = strOr(row, "", "last_active_at", "lastActiveAt");
        u.authProvider = strOr(row, "local", "auth_provider", "authProvider");
        u.phone = strOr(row, "", "phone");
        u.avatarInitials = strOr(row, "", "avatar_initials", "avatarInitials");
        u.isEnterprise = !"local".equals(u.authProvider);
        return u;
    }

    public User findUserById(String userId) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, name, email, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at, last_active_at, auth_provider, phone, avatar_initials FROM users WHERE id = ?",
            userId);
        if (rows.isEmpty()) return null;
        return publicUser(rows.get(0));
    }

    public User findUserByEmail(String email) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, name, email, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at, last_active_at, auth_provider, phone, avatar_initials FROM users WHERE email = ? AND active = 1",
            email);
        if (rows.isEmpty()) return null;
        return publicUser(rows.get(0));
    }

    public Map<String, Object> findRawUserById(String userId) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT *, password_hash FROM users WHERE id = ?", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean hasRight(User user, String right) {
        return "admin".equals(user.role) || (user.rights != null && user.rights.contains(right));
    }

    public List<String> allowedModelIds(User user) {
        if ("admin".equals(user.role)) {
            return db.queryForList("SELECT id FROM models WHERE status IN ('available','configured')", String.class);
        }
        List<String> groupIds = db.queryForList("SELECT group_id FROM user_groups WHERE user_id = ?", String.class, user.id);
        Set<String> ids = new HashSet<>();

        // Access grants by user
        List<String> byUser = db.queryForList(
            "SELECT model_id FROM access_grants WHERE subject_type = 'user' AND subject_id = ? AND can_use = 1", String.class, user.id);
        ids.addAll(byUser);
        // By department
        if (user.departmentId != null) {
            List<String> byDept = db.queryForList(
                "SELECT model_id FROM access_grants WHERE subject_type = 'department' AND subject_id = ? AND can_use = 1", String.class, user.departmentId);
            ids.addAll(byDept);
        }
        // By groups
        for (String gid : groupIds) {
            List<String> byGroup = db.queryForList(
                "SELECT model_id FROM access_grants WHERE subject_type = 'group' AND subject_id = ? AND can_use = 1", String.class, gid);
            ids.addAll(byGroup);
        }

        // Implicit grants from rights
        Set<String> effectiveRights = new HashSet<>();
        if (user.rights != null) effectiveRights.addAll(user.rights);
        effectiveRights.addAll(departmentDefaults(user.departmentId));

        if (effectiveRights.contains("models:local:use") || effectiveRights.contains("models:manage") || effectiveRights.contains("models:external:use")) {
            List<String> localModels = db.queryForList(
                "SELECT id FROM models WHERE provider != 'api' AND status != 'disabled'", String.class);
            ids.addAll(localModels);
        }
        if (effectiveRights.contains("models:external:use") || effectiveRights.contains("api:use")) {
            List<String> apiModels = db.queryForList(
                "SELECT id FROM models WHERE provider = 'api' AND status != 'disabled'", String.class);
            ids.addAll(apiModels);
        }

        // Per-user overrides
        List<Map<String, Object>> overrides = db.queryForList(
            "SELECT permission_key, model_id, effect, expires_at FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC",
            user.id);
        for (Map<String, Object> ov : overrides) {
            String modelId = (String) ov.get("model_id");
            if (modelId == null) continue;
            String expiresAt = (String) ov.get("expires_at");
            if (expiresAt != null && !expiresAt.isBlank()) {
                try {
                    if (System.currentTimeMillis() > java.time.Instant.parse(expiresAt).toEpochMilli()) continue;
                } catch (Exception ignored) {}
            }
            String effect = (String) ov.get("effect");
            if ("allow".equals(effect)) ids.add(modelId);
            if ("deny".equals(effect)) ids.remove(modelId);
        }
        return new ArrayList<>(ids);
    }

    public List<String> departmentDefaults(String departmentId) {
        if ("dept-product".equals(departmentId))
            return Arrays.asList("chat:use", "models:local:use", "models:coding:use", "workspace:build", "files:upload");
        if ("dept-support".equals(departmentId))
            return Arrays.asList("chat:use", "models:local:use", "models:reasoning:use", "files:upload");
        return Arrays.asList("chat:use", "models:local:use");
    }

    public Map<String, Object> effectiveAccess(User user) {
        List<String> rolePerms = new ArrayList<>();
        List<Map<String, Object>> roleCatalogRow = db.queryForList(
            "SELECT permissions FROM role_catalog WHERE id = ?", user.role);
        if (!roleCatalogRow.isEmpty()) {
            rolePerms = safeJsonList((String) roleCatalogRow.get(0).get("permissions"));
        }

        Set<String> permissions = new LinkedHashSet<>();
        if (user.rights != null) permissions.addAll(user.rights);
        permissions.addAll(departmentDefaults(user.departmentId));
        permissions.addAll(rolePerms);

        Set<String> denied = new LinkedHashSet<>();
        List<Map<String, Object>> overrides = db.queryForList(
            "SELECT permission_key, effect, expires_at FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC",
            user.id);
        for (Map<String, Object> ov : overrides) {
            String expiresAt = (String) ov.get("expires_at");
            if (expiresAt != null && !expiresAt.isBlank()) {
                try {
                    if (System.currentTimeMillis() > java.time.Instant.parse(expiresAt).toEpochMilli()) continue;
                } catch (Exception ignored) {}
            }
            String effect = (String) ov.get("effect");
            String key = (String) ov.get("permission_key");
            if ("deny".equals(effect)) denied.add(key);
            if ("allow".equals(effect)) permissions.add(key);
        }
        denied.forEach(permissions::remove);

        List<String> groups = db.queryForList("SELECT group_id FROM user_groups WHERE user_id = ?", String.class, user.id);

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> sortedPerms = new ArrayList<>(permissions);
        Collections.sort(sortedPerms);
        result.put("permissions", sortedPerms);
        List<String> sortedDenied = new ArrayList<>(denied);
        Collections.sort(sortedDenied);
        result.put("denied", sortedDenied);
        result.put("allowedModelIds", allowedModelIds(user));
        result.put("groups", groups);
        result.put("sourcePriority", Arrays.asList("user override", "department policy", "role permission", "organization default"));
        Map<String, Object> quotas = new LinkedHashMap<>();
        quotas.put("dailyTokenLimit", user.dailyTokenLimit);
        quotas.put("monthlyTokenLimit", user.monthlyTokenLimit);
        quotas.put("gpuQuotaMinutes", user.gpuQuotaMinutes);
        quotas.put("vramLimitMb", user.vramLimitMb);
        quotas.put("concurrentModelLimit", user.concurrentModelLimit);
        quotas.put("apiRateLimitPerMinute", user.apiRateLimitPerMinute);
        quotas.put("maxContextSize", user.maxContextSize);
        result.put("quotas", quotas);
        return result;
    }

    public List<Map<String, Object>> userOverrides(String userId) {
        return db.queryForList(
            "SELECT id, user_id AS userId, permission_key AS permissionKey, model_id AS modelId, effect, reason, expires_at AS expiresAt, created_at AS createdAt FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC",
            userId);
    }

    public List<Map<String, Object>> roleCatalog() {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, name, description, permissions, system_role AS systemRole FROM role_catalog ORDER BY name");
        for (Map<String, Object> r : rows) {
            r.put("permissions", safeJsonList((String) r.get("permissions")));
            Object sr = r.get("systemRole");
            r.put("systemRole", sr != null && ((Number) sr).intValue() != 0);
        }
        return rows;
    }

    public List<Map<String, Object>> permissionCatalog() {
        return db.queryForList(
            "SELECT key, category, description, risk_level AS riskLevel FROM permission_catalog ORDER BY category, key");
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

    private String firstStr(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }

    private String strOr(Map<String, Object> row, String def, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) return v.toString();
        }
        return def;
    }

    private boolean boolVal(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) {
                if (v instanceof Boolean) return (Boolean) v;
                if (v instanceof Number) return ((Number) v).intValue() != 0;
                return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
            }
        }
        return false;
    }

    private long longVal(Map<String, Object> row, long def, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) {
                try { return ((Number) v).longValue(); } catch (Exception ignored) {}
            }
        }
        return def;
    }
}
