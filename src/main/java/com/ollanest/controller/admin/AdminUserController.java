package com.ollanest.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController extends BaseController {

    private final JdbcTemplate db;
    private final UserService userService;
    private final ChatService chatService;
    private final AuthService authService;
    private final ObjectMapper mapper;

    private static final String DEFAULT_USER_PASSWORD = "CHANGE_ME_ON_FIRST_BOOT";

    public AdminUserController(JdbcTemplate db, UserService userService, ChatService chatService,
                                AuthService authService, ObjectMapper mapper) {
        this.db = db;
        this.userService = userService;
        this.chatService = chatService;
        this.authService = authService;
        this.mapper = mapper;
    }

    @GetMapping("/sessions/active")
    public ResponseEntity<Map<String, Object>> activeSessions(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT s.token, s.user_id, s.expires_at, u.name, u.email, u.role FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.expires_at > datetime('now') ORDER BY s.expires_at DESC");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", r.get("user_id")); item.put("name", r.get("name"));
            item.put("email", r.get("email")); item.put("role", r.get("role"));
            item.put("expiresAt", r.get("expires_at"));
            String token = (String) r.get("token");
            item.put("token", token != null && token.length() > 8 ? token.substring(0, 8) + "…" : token);
            list.add(item);
        }
        return ResponseEntity.ok(Map.of("sessions", list));
    }

    @DeleteMapping("/sessions/user/{userId}")
    public ResponseEntity<Map<String, Object>> clearUserSessions(@PathVariable String userId, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        authService.invalidateUserSessions(userId);
        db.update("DELETE FROM sessions WHERE user_id = ?", userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpServletRequest req,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "25") int limit,
                                                           @RequestParam(required = false) String search) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        page = Math.max(1, page);
        limit = Math.min(100, limit);
        int offset = (page - 1) * limit;
        String where = search != null && !search.isBlank() ? "WHERE name LIKE ? OR email LIKE ?" : "";
        String likeSearch = search != null ? "%" + search + "%" : null;

        Integer total;
        List<Map<String, Object>> users;
        if (likeSearch != null) {
            total = db.queryForObject("SELECT COUNT(*) FROM users " + where, Integer.class, likeSearch, likeSearch);
            users = db.queryForList("SELECT id, name, email, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at, last_active_at, auth_provider, phone, avatar_initials FROM users " + where + " ORDER BY role, name LIMIT ? OFFSET ?",
                likeSearch, likeSearch, limit, offset);
        } else {
            total = db.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            users = db.queryForList("SELECT id, name, email, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at, last_active_at, auth_provider, phone, avatar_initials FROM users ORDER BY role, name LIMIT ? OFFSET ?",
                limit, offset);
        }
        List<Object> publicUsers = new ArrayList<>();
        for (Map<String, Object> u : users) publicUsers.add(userService.publicUser(u));
        int totalVal = total != null ? total : 0;
        return ResponseEntity.ok(Map.of("users", publicUsers, "total", totalVal, "page", page, "limit", limit,
            "pages", (int) Math.ceil((double) totalVal / limit)));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        String name = (String) body.get("name");
        String email = (String) body.get("email");
        if (name == null || email == null || name.isBlank() || email.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "Name and email are required"));

        String id = uid("u");
        String password = body.get("password") != null ? body.get("password").toString() : DEFAULT_USER_PASSWORD;
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        String role = (String) body.getOrDefault("role", "user");
        String deptId = (String) body.getOrDefault("departmentId", "dept-general");
        String rights = toJson(body.getOrDefault("rights", List.of("chat:use")));

        try {
            db.update("INSERT INTO users (id, name, email, password_hash, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, email, hash, role, rights, deptId,
                body.getOrDefault("employeeId", ""), body.getOrDefault("designation", ""),
                body.getOrDefault("team", ""), body.getOrDefault("branch", ""),
                body.getOrDefault("manager", ""), body.getOrDefault("organization", "Olla Nest"),
                body.getOrDefault("aiAccessTier", "standard"),
                toLong(body.getOrDefault("dailyTokenLimit", 50000)),
                toLong(body.getOrDefault("monthlyTokenLimit", 1000000)),
                toLong(body.getOrDefault("gpuQuotaMinutes", 120)),
                toLong(body.getOrDefault("vramLimitMb", 8192)),
                toLong(body.getOrDefault("concurrentModelLimit", 1)),
                toLong(body.getOrDefault("apiRateLimitPerMinute", 30)),
                toLong(body.getOrDefault("maxContextSize", 8192)),
                Boolean.TRUE.equals(body.get("mfaEnabled")) ? 1 : 0,
                toLong(body.getOrDefault("securityRiskScore", 10)),
                body.getOrDefault("accessStatus", "active"),
                body.getOrDefault("accessExpiresAt", ""));
            db.update("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, 'group-all')", id);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        chatService.appendAudit(admin.name, "admin.user.create", "Created user " + email, null);
        User created = userService.findUserById(id);
        return ResponseEntity.ok(Map.of("ok", true, "user", created,
            "credentials", Map.of("email", email, "password", password, "loginUrl", "/login")));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User user = userService.findUserById(id);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        return ResponseEntity.ok(Map.of("user", user));
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        List<Map<String, Object>> rows = db.queryForList("SELECT id, role FROM users WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        String existingRole = (String) rows.get(0).get("role");

        Object activeVal = body.get("active");
        if (activeVal != null && !Boolean.TRUE.equals(activeVal) && "admin".equals(existingRole))
            return ResponseEntity.status(400).body(Map.of("error", "Admin accounts cannot be deactivated."));

        if (body.containsKey("name")) db.update("UPDATE users SET name = ? WHERE id = ?", body.get("name"), id);
        if (body.containsKey("email")) db.update("UPDATE users SET email = ? WHERE id = ?", body.get("email"), id);
        if (body.containsKey("role")) {
            String newRole = (String) body.get("role");
            if (!Arrays.asList("admin", "user").contains(newRole))
                return ResponseEntity.status(400).body(Map.of("error", "Invalid role."));
            db.update("UPDATE users SET role = ? WHERE id = ?", newRole, id);
        }
        if (body.containsKey("departmentId")) db.update("UPDATE users SET department_id = ? WHERE id = ?", body.get("departmentId"), id);
        if (activeVal != null) db.update("UPDATE users SET active = ? WHERE id = ?", Boolean.TRUE.equals(activeVal) ? 1 : 0, id);
        if (body.containsKey("rights") && body.get("rights") instanceof List) db.update("UPDATE users SET rights = ? WHERE id = ?", toJson(body.get("rights")), id);

        Map<String, String> fieldMap = new LinkedHashMap<>();
        fieldMap.put("employeeId", "employee_id"); fieldMap.put("designation", "designation");
        fieldMap.put("team", "team"); fieldMap.put("branch", "branch"); fieldMap.put("manager", "manager");
        fieldMap.put("organization", "organization"); fieldMap.put("aiAccessTier", "ai_access_tier");
        fieldMap.put("dailyTokenLimit", "daily_token_limit"); fieldMap.put("monthlyTokenLimit", "monthly_token_limit");
        fieldMap.put("gpuQuotaMinutes", "gpu_quota_minutes"); fieldMap.put("vramLimitMb", "vram_limit_mb");
        fieldMap.put("concurrentModelLimit", "concurrent_model_limit"); fieldMap.put("apiRateLimitPerMinute", "api_rate_limit_per_minute");
        fieldMap.put("maxContextSize", "max_context_size"); fieldMap.put("mfaEnabled", "mfa_enabled");
        fieldMap.put("securityRiskScore", "security_risk_score"); fieldMap.put("accessStatus", "access_status");
        fieldMap.put("accessExpiresAt", "access_expires_at");

        for (Map.Entry<String, String> e : fieldMap.entrySet()) {
            if (!body.containsKey(e.getKey())) continue;
            Object val = "mfaEnabled".equals(e.getKey()) ? (Boolean.TRUE.equals(body.get(e.getKey())) ? 1 : 0) : body.get(e.getKey());
            db.update("UPDATE users SET " + e.getValue() + " = ? WHERE id = ?", val, id);
        }

        authService.invalidateUserSessions(id);
        chatService.appendAudit(admin.name, "admin.user.update", "Updated user " + id, null);
        return ResponseEntity.ok(Map.of("ok", true, "user", userService.findUserById(id)));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        if (id.equals(admin.id)) return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete your own account"));
        authService.invalidateUserSessions(id);
        db.update("DELETE FROM users WHERE id = ?", id);
        chatService.appendAudit(admin.name, "admin.user.delete", "Deleted user " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        String newPassword = body.get("password") != null ? body.get("password").toString() : DEFAULT_USER_PASSWORD;
        if (newPassword.length() < 12) return ResponseEntity.status(400).body(Map.of("error", "Password must be at least 12 characters"));
        int changed = db.update("UPDATE users SET password_hash = ? WHERE id = ?", BCrypt.hashpw(newPassword, BCrypt.gensalt(12)), id);
        if (changed == 0) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        authService.invalidateUserSessions(id);
        chatService.appendAudit(admin.name, "admin.user.reset_password", "Reset password for " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/users/{id}/effective-access")
    public ResponseEntity<Map<String, Object>> effectiveAccess(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User user = userService.findUserById(id);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        return ResponseEntity.ok(Map.of("ok", true, "user", user,
            "effectiveAccess", userService.effectiveAccess(user),
            "overrides", userService.userOverrides(id)));
    }

    @PostMapping("/users/{id}/overrides")
    public ResponseEntity<Map<String, Object>> addOverride(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        User user = userService.findUserById(id);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        String permissionKey = body.get("permissionKey") != null ? body.get("permissionKey").toString().trim() : "";
        String effect = body.get("effect") != null ? body.get("effect").toString() : "allow";
        if (permissionKey.isBlank()) return ResponseEntity.status(400).body(Map.of("error", "Permission is required"));
        if (!Arrays.asList("allow", "deny").contains(effect)) return ResponseEntity.status(400).body(Map.of("error", "Effect must be allow or deny"));
        db.update("INSERT INTO user_overrides (id, user_id, permission_key, model_id, effect, reason, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            uid("override"), user.id, permissionKey, body.get("modelId"), effect,
            body.getOrDefault("reason", ""), body.getOrDefault("expiresAt", ""), Instant.now().toString());
        chatService.appendAudit(admin.name, "admin.access.override", effect.toUpperCase() + " " + permissionKey + " for " + user.email, null);
        return ResponseEntity.ok(Map.of("ok", true,
            "effectiveAccess", userService.effectiveAccess(user),
            "overrides", userService.userOverrides(id)));
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<Map<String, Object>> deleteOverride(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);
        int changed = db.update("DELETE FROM user_overrides WHERE id = ?", id);
        if (changed == 0) return ResponseEntity.status(404).body(Map.of("error", "Override not found"));
        chatService.appendAudit(admin.name, "admin.access.override.delete", "Deleted override " + id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // Bulk overrides
    @GetMapping("/overrides")
    public ResponseEntity<Map<String, Object>> listOverrides(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, user_id AS userId, permission_key AS permissionKey, model_id AS modelId, effect, reason, expires_at AS expiresAt, created_at AS createdAt FROM user_overrides ORDER BY created_at DESC");
        return ResponseEntity.ok(Map.of("ok", true, "overrides", rows));
    }

    @PatchMapping("/overrides")
    public ResponseEntity<Map<String, Object>> bulkUpdateOverrides(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        // Placeholder for bulk update — return ok
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
            + Long.toString((long)(Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }

    private long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0; }
    }
}
