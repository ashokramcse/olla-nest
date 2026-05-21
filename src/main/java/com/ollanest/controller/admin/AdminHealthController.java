package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.MonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminHealthController extends BaseController {

    private final JdbcTemplate db;
    private final MonitorService monitorService;

    public AdminHealthController(JdbcTemplate db, MonitorService monitorService) {
        this.db = db;
        this.monitorService = monitorService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;

        Map<String, Object> snapshot = monitorService.getSnapshot();

        Integer userCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE active = 1", Integer.class);
        Integer modelCount = db.queryForObject("SELECT COUNT(*) FROM models WHERE status = 'active'", Integer.class);
        Integer sessionCount = db.queryForObject("SELECT COUNT(*) FROM sessions WHERE expires_at > ?", Integer.class,
            java.time.Instant.now().toString());
        Integer chatCount = db.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
        Integer providerCount = db.queryForObject("SELECT COUNT(*) FROM api_providers WHERE enabled = 1", Integer.class);

        Map<String, Object> db_stats = new LinkedHashMap<>();
        db_stats.put("activeUsers", userCount != null ? userCount : 0);
        db_stats.put("activeModels", modelCount != null ? modelCount : 0);
        db_stats.put("activeSessions", sessionCount != null ? sessionCount : 0);
        db_stats.put("totalChats", chatCount != null ? chatCount : 0);
        db_stats.put("enabledProviders", providerCount != null ? providerCount : 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("status", "healthy");
        result.put("uptime_ms", snapshot.get("uptime_ms"));
        result.put("memory", snapshot.get("memory"));
        result.put("requests", snapshot.get("requests"));
        result.put("errors", snapshot.get("errors"));
        result.put("db", db_stats);
        return ResponseEntity.ok(result);
    }
}
