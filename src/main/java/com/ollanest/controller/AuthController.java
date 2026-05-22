package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController extends BaseController {

    private final AuthService authService;
    private final UserService userService;
    private final DatabaseService databaseService;
    private final ChatService chatService;
    private final JdbcTemplate db;

    private static final int LOGIN_MAX_ATTEMPTS = 10;
    private static final long LOGIN_WINDOW_MS = 15 * 60 * 1000;

    @Value("${app.trusted-proxy:}")
    private String trustedProxy;

    public AuthController(AuthService authService, UserService userService,
                          DatabaseService databaseService, ChatService chatService, JdbcTemplate db) {
        this.authService = authService;
        this.userService = userService;
        this.databaseService = databaseService;
        this.chatService = chatService;
        this.db = db;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest req, HttpServletResponse res) {
        String remoteAddr = req.getRemoteAddr() != null ? req.getRemoteAddr() : "unknown";
        String ip;
        if (trustedProxy != null && !trustedProxy.isBlank() && trustedProxy.equals(remoteAddr)) {
            String forwarded = req.getHeader("x-forwarded-for");
            ip = forwarded != null ? forwarded.split(",")[0].trim() : remoteAddr;
        } else {
            ip = remoteAddr;
        }
        long now = System.currentTimeMillis();

        // Rate limit check
        List<Map<String, Object>> attempts = db.queryForList(
            "SELECT count, reset_at FROM login_attempts WHERE ip = ?", ip);
        long count = 0;
        long resetAt = now + LOGIN_WINDOW_MS;
        if (!attempts.isEmpty()) {
            count = ((Number) attempts.get(0).get("count")).longValue();
            resetAt = ((Number) attempts.get(0).get("reset_at")).longValue();
            if (now > resetAt) { count = 0; resetAt = now + LOGIN_WINDOW_MS; }
        }
        if (count >= LOGIN_MAX_ATTEMPTS) {
            long retryAfter = (resetAt - now) / 1000;
            return ResponseEntity.status(429).body(Map.of("error",
                "Too many login attempts. Try again in " + (int)Math.ceil(retryAfter / 60.0) + " minutes."));
        }

        String email = (String) body.get("email");
        String password = (String) body.get("password");
        if (email == null || password == null || email.isBlank() || password.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "Email and password are required"));
        }

        // Look up user with password hash — also enforce access_expires_at
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, name, email, role, rights, department_id, active, employee_id, designation, team, branch, manager, organization, ai_access_tier, daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute, max_context_size, mfa_enabled, security_risk_score, access_status, access_expires_at, last_active_at, auth_provider, phone, avatar_initials, password_hash FROM users WHERE email = ? AND active = 1 AND (access_expires_at IS NULL OR access_expires_at = '' OR access_expires_at > datetime('now'))",
            email);

        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
        String storedHash = row != null ? (String) row.get("password_hash") : null;

        if (row == null || storedHash == null || !BCrypt.checkpw(password, storedHash)) {
            // Increment attempts
            db.update("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, ?, ?)",
                ip, count + 1, resetAt);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password"));
        }

        // Success — clear attempts
        db.update("DELETE FROM login_attempts WHERE ip = ?", ip);
        User user = userService.publicUser(row);
        authService.setSession(res, req, user);
        chatService.appendAudit(user.name, "auth.login", "User signed in", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("user", user);
        result.put("redirectTo", "admin".equals(user.role) ? "/admin" : "/app");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req, HttpServletResponse res) {
        if (req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String token = authService.getToken(req);
        authService.clearSession(res, token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest req) {
        User user = getUser(req);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", user != null);
        result.put("user", user);
        return ResponseEntity.ok(result);
    }
}
