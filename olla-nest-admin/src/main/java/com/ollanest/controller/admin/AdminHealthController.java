package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.MonitorService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only health check endpoint for the Olla Nest server.
 *
 * <p>
 * GET {@code /api/admin/health} returns JVM memory usage, server uptime, HTTP
 * request/error counters from {@link MonitorService}, and DB-level stats
 * (active users, active models, live sessions, total chats, enabled providers).
 *
 * <p>
 * All fields in the response are named consistently with the frontend
 * dashboard: {@code uptimeMs}, {@code memoryUsedMb}, {@code memoryTotalMb},
 * {@code requests}, {@code errors}.
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>A key-name bug was fixed in v2026.1.0 (commit 95f508b): the original
 * response used different key names ({@code uptime}, {@code usedMb}) that did
 * not match what the frontend expected.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0 — initial Java Spring Boot migration
 * @version v2026.1.0 — bug fix: fixed key name mismatch (uptimeMs,
 *          memoryUsedMb, memoryTotalMb)
 */
@RestController
@RequestMapping("/api/admin")
public class AdminHealthController extends BaseController {

	/** JDBC template for DB-level health statistics. */
	private final JdbcTemplate db;

	/** Provides JVM-level metrics: uptime, memory, request and error counts. */
	private final MonitorService monitorService;

	/**
	 * Constructor-injects the JDBC template and monitor service.
	 *
	 * @param db             the JDBC template
	 * @param monitorService the in-memory JVM metrics service
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AdminHealthController(JdbcTemplate db, MonitorService monitorService) {
		this.db = db;
		this.monitorService = monitorService;
	}

	/**
	 * Returns a health snapshot for the admin dashboard.
	 *
	 * <p>
	 * The response includes:
	 * <ul>
	 * <li>{@code ok} — always {@code true} (server is running)</li>
	 * <li>{@code status} — always {@code "healthy"}</li>
	 * <li>{@code uptimeMs} — JVM uptime in milliseconds</li>
	 * <li>{@code memoryUsedMb} / {@code memoryTotalMb} — heap usage</li>
	 * <li>{@code requests} / {@code errors} — counters since JVM start</li>
	 * <li>{@code db} — sub-object with activeUsers, activeModels, activeSessions,
	 * totalChats, enabledProviders</li>
	 * </ul>
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with the health map, or 401/403 if not an admin
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — bug fix: corrected key names in response
	 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;

		Map<String, Object> snapshot = monitorService.getSnapshot();

		Integer userCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE active = 1", Integer.class);
		Integer modelCount = db.queryForObject("SELECT COUNT(*) FROM models WHERE status = 'active'", Integer.class);
		Integer sessionCount = db.queryForObject("SELECT COUNT(*) FROM sessions WHERE expires_at > ?", Integer.class,
				Instant.now().toString());
		Integer chatCount = db.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
		Integer providerCount = db.queryForObject("SELECT COUNT(*) FROM api_providers WHERE enabled = 1",
				Integer.class);

		Map<String, Object> dbStats = new LinkedHashMap<>();
		dbStats.put("activeUsers", userCount != null ? userCount : 0);
		dbStats.put("activeModels", modelCount != null ? modelCount : 0);
		dbStats.put("activeSessions", sessionCount != null ? sessionCount : 0);
		dbStats.put("totalChats", chatCount != null ? chatCount : 0);
		dbStats.put("enabledProviders", providerCount != null ? providerCount : 0);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("status", "healthy");
		result.put("uptimeMs", snapshot.get("uptimeMs"));
		result.put("memoryUsedMb", snapshot.get("memoryUsedMb"));
		result.put("memoryTotalMb", snapshot.get("memoryTotalMb"));
		result.put("requests", snapshot.get("requests"));
		result.put("errors", snapshot.get("errors"));
		result.put("db", dbStats);
		return ResponseEntity.ok(result);
	}
}
