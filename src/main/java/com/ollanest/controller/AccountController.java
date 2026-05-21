package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/account")
public class AccountController extends BaseController {

    private final JdbcTemplate db;
    private final UserService userService;
    private final ChatService chatService;

    public AccountController(JdbcTemplate db, UserService userService, ChatService chatService) {
        this.db = db;
        this.userService = userService;
        this.chatService = chatService;
    }

    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        String newPassword = (String) body.get("newPassword");
        if (newPassword == null || newPassword.length() < 12)
            return ResponseEntity.status(400).body(Map.of("error", "New password must be at least 12 characters"));
        List<Map<String, Object>> rows = db.queryForList("SELECT password_hash FROM users WHERE id = ?", user.id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        String storedHash = (String) rows.get(0).get("password_hash");
        String currentPassword = (String) body.get("currentPassword");
        if (storedHash == null || currentPassword == null || !BCrypt.checkpw(currentPassword, storedHash))
            return ResponseEntity.status(401).body(Map.of("error", "Current password is incorrect"));
        db.update("UPDATE users SET password_hash = ? WHERE id = ?", BCrypt.hashpw(newPassword, BCrypt.gensalt(12)), user.id);
        chatService.appendAudit(user.name, "account.password.change", "Changed own password", null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> rows = db.queryForList("SELECT auth_provider FROM users WHERE id = ?", user.id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        String authProvider = (String) rows.get(0).getOrDefault("auth_provider", "local");
        boolean isEnterprise = !"local".equals(authProvider);

        List<String> allowed = new ArrayList<>(Arrays.asList("name", "phone", "avatar_initials"));
        if (!isEnterprise) allowed.addAll(Arrays.asList("designation", "team", "branch"));

        List<String> setClauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String field : allowed) {
            String camel = snakeToCamel(field);
            if (body.containsKey(camel)) {
                setClauses.add(field + " = ?");
                values.add(String.valueOf(body.get(camel)));
            }
        }
        if (setClauses.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "No updatable fields provided"));
        values.add(user.id);
        db.update("UPDATE users SET " + String.join(", ", setClauses) + " WHERE id = ?", values.toArray());
        User updated = userService.findUserById(user.id);
        chatService.appendAudit(updated.name, "account.profile.update", "Updated own profile", null);
        return ResponseEntity.ok(Map.of("ok", true, "user", updated));
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        User fresh = userService.findUserById(user.id);
        if (fresh == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        String deptName = "";
        if (fresh.departmentId != null) {
            List<Map<String, Object>> depts = db.queryForList("SELECT name FROM departments WHERE id = ?", fresh.departmentId);
            if (!depts.isEmpty()) deptName = (String) depts.get(0).get("name");
        }
        return ResponseEntity.ok(Map.of("user", fresh, "departmentName", deptName));
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);

        // Today at midnight
        String todayStart = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toString();
        // Month start
        Instant monthStart = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        String monthStartStr = monthStart.toString();

        Integer todayTokens = db.queryForObject(
            "SELECT COALESCE(SUM(m.tokens_used),0) FROM chat_messages m JOIN chat_sessions s ON s.id = m.session_id WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?",
            Integer.class, user.id, todayStart);
        Integer monthTokens = db.queryForObject(
            "SELECT COALESCE(SUM(m.tokens_used),0) FROM chat_messages m JOIN chat_sessions s ON s.id = m.session_id WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?",
            Integer.class, user.id, monthStartStr);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokensUsedToday", todayTokens != null ? todayTokens : 0);
        result.put("dailyTokenLimit", user.dailyTokenLimit > 0 ? user.dailyTokenLimit : 50000);
        result.put("tokensUsedMonth", monthTokens != null ? monthTokens : 0);
        result.put("monthlyTokenLimit", user.monthlyTokenLimit > 0 ? user.monthlyTokenLimit : 1000000);
        return ResponseEntity.ok(result);
    }

    private String snakeToCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean next = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { next = true; }
            else if (next) { sb.append(Character.toUpperCase(c)); next = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
