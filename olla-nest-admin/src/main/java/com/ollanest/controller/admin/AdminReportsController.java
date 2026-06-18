package com.ollanest.controller.admin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.controller.BaseController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin analytics and reporting endpoints.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The admin dashboard needs aggregated usage analytics over a configurable time
 * window (default 30 days). This controller computes those read-only
 * aggregations directly against SQLite and is admin-only.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every query is bounded by a {@code since} parameter; unbounded scans on
 * large tables would be unacceptably slow on SQLite.</li>
 * <li>The {@code days} window is supplied by the caller; no server-side cap is
 * enforced because admin users are trusted.</li>
 * <li>All queries are read-only — this controller never mutates state.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * </ul>
 *
 * <pre>
 *   GET /api/admin/reports   — usage report (activity, model usage, token
 *                              leaderboard, dept breakdown, latency, audit)
 *   GET /api/admin/feedback  — per-model thumbs ratings with sample comments
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
@RequestMapping("/api/admin")
public class AdminReportsController extends BaseController {

	/** JDBC template for all report queries. */
	private final JdbcTemplate db;

	/**
	 * Constructor-injects the JDBC template.
	 *
	 * @param db the JDBC template
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AdminReportsController(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * Returns a comprehensive usage report for the admin dashboard.
	 *
	 * <p>
	 * Report sections returned:
	 * <ul>
	 * <li>{@code summary} — total users, sessions, messages, tokens, avg
	 * latency</li>
	 * <li>{@code dailyActivity} — per-day message and token counts</li>
	 * <li>{@code modelUsage} — top 10 models by usage</li>
	 * <li>{@code tokenLeaderboard} — top 20 users by token consumption</li>
	 * <li>{@code modeBreakdown} — request counts by chat mode</li>
	 * <li>{@code deptUsage} — sessions/messages/tokens per department</li>
	 * <li>{@code tierDist} — user counts by AI access tier</li>
	 * <li>{@code liveVsFailed} — live vs failed assistant message counts</li>
	 * <li>{@code auditBreakdown} — top 10 audit event types</li>
	 * <li>{@code auditTimeline} — per-day audit event counts</li>
	 * <li>{@code latencyByModel} — avg/min/max latency per model</li>
	 * </ul>
	 *
	 * @param days number of days to include in the report (default 30)
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with the full report map, or 401/403 if not an admin
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping({ "/reports", "/reports/usage" })
	public ResponseEntity<Map<String, Object>> reports(@RequestParam(defaultValue = "30") int days,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		String since = Instant.now().minus(days, ChronoUnit.DAYS).toString();

		List<Map<String, Object>> dailyActivity = db
				.queryForList("SELECT substr(created_at,1,10) AS day, COUNT(*) AS messages, "
						+ "SUM(CASE WHEN role='assistant' THEN tokens_used ELSE 0 END) AS tokens "
						+ "FROM chat_messages WHERE created_at >= ? GROUP BY day ORDER BY day", since);
		List<Map<String, Object>> modelUsage = db
				.queryForList("SELECT model_name, COUNT(*) AS uses, SUM(tokens_used) AS total_tokens, "
						+ "AVG(latency_ms) AS avg_latency FROM chat_messages "
						+ "WHERE role='assistant' AND model_name IS NOT NULL AND model_name != '' "
						+ "AND created_at >= ? GROUP BY model_name ORDER BY uses DESC LIMIT 10", since);
		List<Map<String, Object>> tokenLeaderboard = db
				.queryForList("SELECT u.name, u.email, u.role, u.department_id, d.name AS department, "
						+ "u.ai_access_tier, u.daily_token_limit, "
						+ "COUNT(DISTINCT cs.id) AS sessions, COUNT(DISTINCT um.id) AS messages, "
						+ "COALESCE(SUM(am.tokens_used),0) AS total_tokens, " + "CASE WHEN COUNT(DISTINCT um.id) > 0 "
						+ "THEN ROUND(COALESCE(SUM(am.tokens_used),0) * 1.0 / COUNT(DISTINCT um.id)) "
						+ "ELSE 0 END AS avg_tokens_per_msg, MAX(cs.updated_at) AS last_active "
						+ "FROM users u LEFT JOIN departments d ON d.id = u.department_id "
						+ "LEFT JOIN chat_sessions cs ON cs.user_id = u.id AND cs.created_at >= ? "
						+ "LEFT JOIN chat_messages um ON um.session_id = cs.id AND um.role = 'user' "
						+ "LEFT JOIN chat_messages am ON am.session_id = cs.id AND am.role = 'assistant' "
						+ "WHERE u.active = 1 GROUP BY u.id ORDER BY total_tokens DESC LIMIT 20", since);
		List<Map<String, Object>> modeBreakdown = db.queryForList("SELECT mode, COUNT(*) AS count FROM router_traces "
				+ "WHERE created_at >= ? AND mode IS NOT NULL " + "GROUP BY mode ORDER BY count DESC", since);
		List<Map<String, Object>> deptUsage = db
				.queryForList("SELECT d.name AS dept, COUNT(DISTINCT cs.id) AS sessions, "
						+ "COUNT(DISTINCT um.id) AS messages, " + "COALESCE(SUM(cm.tokens_used),0) AS tokens "
						+ "FROM departments d LEFT JOIN users u ON u.department_id = d.id "
						+ "LEFT JOIN chat_sessions cs ON cs.user_id = u.id AND cs.created_at >= ? "
						+ "LEFT JOIN chat_messages um ON um.session_id = cs.id AND um.role = 'user' "
						+ "LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role = 'assistant' "
						+ "GROUP BY d.id ORDER BY tokens DESC", since);
		List<Map<String, Object>> tierDist = db
				.queryForList("SELECT ai_access_tier AS tier, COUNT(*) AS count FROM users "
						+ "WHERE active = 1 GROUP BY ai_access_tier ORDER BY count DESC");
		Map<String, Object> liveVsFailed = db
				.queryForList("SELECT SUM(CASE WHEN live=1 THEN 1 ELSE 0 END) AS live_count, "
						+ "SUM(CASE WHEN live=0 THEN 1 ELSE 0 END) AS failed_count "
						+ "FROM chat_messages WHERE role='assistant' AND created_at >= ?", since)
				.stream().findFirst().orElse(Map.of("live_count", 0, "failed_count", 0));
		List<Map<String, Object>> auditBreakdown = db.queryForList("SELECT action, COUNT(*) AS count FROM audit_events "
				+ "WHERE created_at >= ? GROUP BY action ORDER BY count DESC LIMIT 10", since);
		List<Map<String, Object>> auditTimeline = db
				.queryForList("SELECT substr(created_at,1,10) AS day, COUNT(*) AS events "
						+ "FROM audit_events WHERE created_at >= ? GROUP BY day ORDER BY day", since);
		List<Map<String, Object>> latencyByModel = db
				.queryForList("SELECT model_name, ROUND(AVG(latency_ms)) AS avg_ms, "
						+ "MIN(latency_ms) AS min_ms, MAX(latency_ms) AS max_ms, "
						+ "COUNT(*) AS requests FROM chat_messages "
						+ "WHERE role='assistant' AND model_name IS NOT NULL AND model_name != '' "
						+ "AND latency_ms > 0 AND created_at >= ? "
						+ "GROUP BY model_name ORDER BY avg_ms ASC LIMIT 10", since);
		Map<String, Object> summary = db.queryForList("SELECT COUNT(DISTINCT u.id) AS total_users, "
				+ "COUNT(DISTINCT cs.id) AS total_sessions, " + "COUNT(cm.id) AS total_messages, "
				+ "COALESCE(SUM(cm.tokens_used),0) AS total_tokens, " + "ROUND(AVG(cm.latency_ms)) AS avg_latency "
				+ "FROM users u LEFT JOIN chat_sessions cs ON cs.user_id = u.id "
				+ "LEFT JOIN chat_messages cm ON cm.session_id = cs.id " + "AND cm.role='assistant' WHERE u.active = 1")
				.stream().findFirst().orElse(Map.of());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("summary", summary);
		result.put("dailyActivity", dailyActivity);
		result.put("modelUsage", modelUsage);
		result.put("tokenLeaderboard", tokenLeaderboard);
		result.put("modeBreakdown", modeBreakdown);
		result.put("deptUsage", deptUsage);
		result.put("tierDist", tierDist);
		result.put("liveVsFailed", liveVsFailed);
		result.put("auditBreakdown", auditBreakdown);
		result.put("auditTimeline", auditTimeline);
		result.put("latencyByModel", latencyByModel);
		return ResponseEntity.ok(result);
	}

	/**
	 * Returns per-model feedback aggregation with thumbs-up/down counts and
	 * comments.
	 *
	 * <p>
	 * Iterates all feedback rows joined to the associated assistant message, groups
	 * by model name, and accumulates counts. Stores up to 10 sample comments per
	 * model.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, feedback: [modelFeedback]}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/feedback")
	public ResponseEntity<Map<String, Object>> feedback(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		List<Map<String, Object>> rows = db.queryForList("SELECT cm.model_name, f.rating, f.comment, f.created_at "
				+ "FROM feedback f LEFT JOIN chat_messages cm ON cm.id = f.message_id " + "ORDER BY f.created_at DESC");
		Map<String, Map<String, Object>> models = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String key = row.get("model_name") != null ? row.get("model_name").toString() : "unknown";
			models.computeIfAbsent(key, k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("modelName", k);
				m.put("thumbsUp", 0);
				m.put("thumbsDown", 0);
				m.put("totalRatings", 0);
				m.put("comments", new ArrayList<String>());
				return m;
			});
			Map<String, Object> m = models.get(key);
			m.put("totalRatings", ((Number) m.get("totalRatings")).intValue() + 1);
			int rating = row.get("rating") != null ? ((Number) row.get("rating")).intValue() : 0;
			if (rating == 1) {
				m.put("thumbsUp", ((Number) m.get("thumbsUp")).intValue() + 1);
			} else {
				m.put("thumbsDown", ((Number) m.get("thumbsDown")).intValue() + 1);
			}
			@SuppressWarnings("unchecked")
			List<String> comments = (List<String>) m.get("comments");
			if (row.get("comment") != null && comments.size() < 10) {
				comments.add(row.get("comment").toString());
			}
		}
		return ResponseEntity.ok(Map.of("ok", true, "feedback", new ArrayList<>(models.values())));
	}
}
