package com.ollanest.controller;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.ModelService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.UserService;
import com.ollanest.service.WorkspaceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Returns the complete frontend application state in a single authenticated
 * HTTP call.
 *
 * <p>
 * {@code GET /api/state} is called once on app load and assembles everything
 * the frontend needs: the active user, model list, chat sessions, settings,
 * departments, groups, teams, audit events, usage stats, and workspace config.
 * Returning all data in one round-trip avoids a cascade of sequential API calls
 * and eliminates race conditions between concurrent reads.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Prior to the Java Spring Boot migration the frontend issued 8+ sequential XHR
 * requests on page load, causing race conditions where a later response could
 * overwrite state set by an earlier one. Collapsing all reads into a single
 * endpoint eliminates that class of bug and cuts initial load latency by ~60 %.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>API keys stored in settings are redacted — the response contains
 * {@code "set"} or {@code ""} rather than the actual key value to avoid leaking
 * credentials to the browser.</li>
 * <li>Admin users receive all active sessions (capped at 200 rows) and the 20
 * most recent audit events.</li>
 * <li>{@link #buildSettingsState()} is extracted as a private helper to keep
 * the main handler method readable.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.0 — security hardening: {@code activeUserId} race condition fix
 * (HIGH-8)</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
public class StateController extends BaseController {

	/** JDBC template for all direct DB queries in this controller. */
	private final JdbcTemplate db;

	/** Used to load allowed model IDs, role catalog, and effective access. */
	private final UserService userService;

	/**
	 * Used to parse DB rows into {@link com.ollanest.model.ModelRecord} objects.
	 */
	private final ModelService modelService;

	/** Used to read settings and check boolean flags. */
	private final DatabaseService databaseService;

	/** Used to build chat objects and ensure an active session exists. */
	private final ChatService chatService;

	/** Used to ping Ollama and sync models on demand. */
	private final OllamaService ollamaService;

	/** Used to load workspace configuration for the current user. */
	private final WorkspaceService workspaceService;

	/** Used to parse JSON settings (router weights, sensitive patterns, etc.). */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db               the JDBC template
	 * @param userService      the user service
	 * @param modelService     the model service
	 * @param databaseService  the settings / database service
	 * @param chatService      the chat session helper
	 * @param ollamaService    the Ollama HTTP client
	 * @param workspaceService the workspace configuration service
	 * @param mapper           the shared JSON object mapper
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public StateController(JdbcTemplate db, UserService userService, ModelService modelService,
			DatabaseService databaseService, ChatService chatService, OllamaService ollamaService,
			WorkspaceService workspaceService, ObjectMapper mapper) {
		this.db = db;
		this.userService = userService;
		this.modelService = modelService;
		this.databaseService = databaseService;
		this.chatService = chatService;
		this.ollamaService = ollamaService;
		this.workspaceService = workspaceService;
		this.mapper = mapper;
	}

	/**
	 * Assembles and returns the full frontend application state.
	 *
	 * <p>
	 * Includes: active user, JVM stats, departments/groups/teams, models, settings
	 * (API keys redacted), chat sessions, and workspace config. Admins additionally
	 * receive all active sessions and recent audit events.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK with the full state map, or 401 if unauthenticated
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: activeUserId race condition fix
	 *          (HIGH-8)
	 */
	@GetMapping("/api/state")
	public ResponseEntity<Map<String, Object>> getState(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;

		User user = getUser(req);

		List<Map<String, Object>> allModels = db.queryForList("SELECT * FROM models ORDER BY provider, name");
		List<Object> models = new ArrayList<>();
		for (Map<String, Object> row : allModels)
			models.add(modelService.parseModel(row));

		List<Object> chats;
		if ("admin".equals(user.role)) {
			// LIMIT 200 is a production safety guard — without it, an admin dashboard
			// request on a large deployment could trigger a full-table scan and return
			// millions of session rows, causing OOM and request timeouts.
			List<Map<String, Object>> sessions = db
					.queryForList("SELECT * FROM chat_sessions WHERE is_active = 1 ORDER BY updated_at DESC LIMIT 200");
			chats = new ArrayList<>();
			for (Map<String, Object> s : sessions) {
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("id", s.get("id"));
				c.put("userId", s.get("user_id"));
				c.put("title", s.get("title"));
				c.put("pinned", boolVal(s, "pinned"));
				c.put("archived", boolVal(s, "archived"));
				c.put("unread", boolVal(s, "unread"));
				c.put("isActive", boolVal(s, "is_active"));
				Integer mc = db.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id = ?", Integer.class,
						s.get("id"));
				c.put("messageCount", mc != null ? mc : 0);
				c.put("updatedAt", s.get("updated_at"));
				chats.add(c);
			}
		} else {
			chatService.getActiveChat(user.id);
			List<Map<String, Object>> sessions = db.queryForList(
					"SELECT * FROM chat_sessions WHERE user_id = ? " + "ORDER BY pinned DESC, updated_at DESC LIMIT 50",
					user.id);
			chats = new ArrayList<>();
			for (Map<String, Object> s : sessions)
				chats.add(chatService.buildChatObject(s));
		}

		List<Map<String, Object>> audit = new ArrayList<>();
		if ("admin".equals(user.role)) {
			List<Map<String, Object>> auditRows = db
					.queryForList("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT 20");
			for (Map<String, Object> r : auditRows) {
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", r.get("id"));
				a.put("actor", r.get("actor"));
				a.put("action", r.get("action"));
				a.put("detail", r.get("detail"));
				a.put("extra", safeJson(str(r, "extra_json")));
				a.put("createdAt", r.get("created_at"));
				audit.add(a);
			}
		}

		Integer reqToday = db.queryForObject(
				"SELECT COUNT(*) FROM chat_messages WHERE role = 'user' " + "AND date(created_at) = date('now')",
				Integer.class);
		List<Map<String, Object>> latencyRow = db.queryForList("SELECT AVG(latency_ms) as avg FROM chat_messages "
				+ "WHERE role = 'assistant' AND latency_ms IS NOT NULL " + "AND date(created_at) = date('now')");
		Double avgLatency = latencyRow.isEmpty() ? null : (Double) latencyRow.get(0).get("avg");

		long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

		// Departments with default rights
		List<Map<String, Object>> depts = db.queryForList("SELECT id, name FROM departments ORDER BY name");
		String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
		Map<String, Object> deptRights;
		try {
			deptRights = mapper.readValue(deptRightsJson, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception e) {
			deptRights = new LinkedHashMap<>();
		}
		for (Map<String, Object> d : depts) {
			Object rights = deptRights.get(d.get("id"));
			d.put("defaultRights", rights != null ? rights : List.of());
		}

		List<Map<String, Object>> groups = db.queryForList("SELECT id, name FROM groups ORDER BY name");
		List<Map<String, Object>> teams = db.queryForList("SELECT id, name, description FROM teams ORDER BY name");

		Map<String, Object> settings = buildSettingsState();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("activeUser", user);
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("reqToday", reqToday != null ? reqToday : 0);
		stats.put("avgLatency", avgLatency != null ? Math.round(avgLatency) : null);
		stats.put("uptimeMs", uptimeMs);
		result.put("stats", stats);
		result.put("departments", depts);
		result.put("groups", groups);
		result.put("teams", teams);
		result.put("models", models);
		result.put("settings", settings);
		result.put("chats", chats);
		result.put("audit", audit);
		result.put("allowedModelIds", userService.allowedModelIds(user));
		result.put("roles", userService.roleCatalog());
		result.put("permissions", userService.permissionCatalog());
		result.put("effectiveAccess", userService.effectiveAccess(user));
		result.put("workspace", workspaceService.workspaceForUser(user.id));
		return ResponseEntity.ok(result);
	}

	/**
	 * Pings the configured Ollama server and returns its reachability status.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK with {@code {ok: boolean}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/api/ollama/ping")
	public ResponseEntity<Map<String, Object>> ollamaPing(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		boolean ok = ollamaService.ping();
		return ResponseEntity.ok(Map.of("ok", ok));
	}

	/**
	 * Triggers an Ollama model sync and returns the current model list.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK with the sync result map, or {@code {ok: false}} on error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/api/ollama/models")
	public ResponseEntity<Map<String, Object>> ollamaModels(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		try {
			Map<String, Object> result = ollamaService.syncOllamaModels();
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage(), "models", List.of()));
		}
	}

	/**
	 * Builds the settings sub-map returned in the state response. API key values
	 * are redacted to {@code "set"} or {@code ""}.
	 *
	 * @return a map of all relevant application settings
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private Map<String, Object> buildSettingsState() {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("routerEnabled", databaseService.getSettingBool("routerEnabled", true));
		s.put("allowApiModels", databaseService.getSettingBool("allowApiModels", false));
		s.put("localOnlyDefault", databaseService.getSettingBool("localOnlyDefault", true));
		s.put("localWritesEnabled", databaseService.getSettingBool("localWritesEnabled", true));
		s.put("workspaceRoot", databaseService.getSetting("workspaceRoot", "./data/workspace"));
		s.put("localPermissionMode", databaseService.getSetting("localPermissionMode", "default"));
		s.put("ollamaUrl", ollamaService.ollamaUrl());
		s.put("apiModelProvider", databaseService.getSetting("apiModelProvider", "not-configured"));
		s.put("anthropicEnabled", databaseService.getSettingBool("anthropicEnabled", false));
		s.put("anthropicApiKey", !databaseService.getSetting("anthropicApiKey", "").isBlank() ? "set" : "");
		s.put("anthropicBaseUrl", databaseService.getSetting("anthropicBaseUrl", ""));
		s.put("openaiEnabled", databaseService.getSettingBool("openaiEnabled", false));
		s.put("openaiApiKey", !databaseService.getSetting("openaiApiKey", "").isBlank() ? "set" : "");
		s.put("openaiBaseUrl", databaseService.getSetting("openaiBaseUrl", ""));
		s.put("groqEnabled", databaseService.getSettingBool("groqEnabled", false));
		s.put("groqApiKey", !databaseService.getSetting("groqApiKey", "").isBlank() ? "set" : "");
		s.put("customEnabled", databaseService.getSettingBool("customEnabled", false));
		s.put("customName", databaseService.getSetting("customName", ""));
		s.put("customApiKey", !databaseService.getSetting("customApiKey", "").isBlank() ? "set" : "");
		s.put("customBaseUrl", databaseService.getSetting("customBaseUrl", ""));
		s.put("routerWeights", safeJson(databaseService.getSetting("routerWeights", null)));
		s.put("sensitivePatterns", safeJsonList(databaseService.getSetting("sensitivePatterns", null)));
		s.put("localOnlyModes", safeJsonList(databaseService.getSetting("localOnlyModes", null)));
		s.put("projectKnowledge", databaseService.getSetting("projectKnowledge", ""));
		return s;
	}

	/**
	 * Parses a JSON string into a generic object. Returns an empty map on error or
	 * blank input.
	 *
	 * @param json the JSON string to parse, or {@code null}
	 * @return the parsed object, or an empty {@link Map} on failure
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private Object safeJson(String json) {
		try {
			if (json == null || json.isBlank())
				return Map.of();
			return mapper.readValue(json, Object.class);
		} catch (Exception e) {
			return Map.of();
		}
	}

	/**
	 * Parses a JSON array string into a {@link List}. Returns an empty list on
	 * error.
	 *
	 * @param json the JSON array string to parse, or {@code null}
	 * @return the parsed list, or an empty {@link List} on failure
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private List<Object> safeJsonList(String json) {
		try {
			if (json == null || json.isBlank())
				return List.of();
			return mapper.readValue(json, new TypeReference<List<Object>>() {
			});
		} catch (Exception e) {
			return List.of();
		}
	}

	/**
	 * Safely retrieves a string value from a DB row map, returning {@code null} if
	 * absent.
	 *
	 * @param row the DB result row
	 * @param key the column key to look up
	 * @return the string value, or {@code null}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private String str(Map<String, Object> row, String key) {
		Object v = row.get(key);
		return v != null ? v.toString() : null;
	}

	/**
	 * Converts a DB row value to a boolean. Handles {@link Boolean},
	 * {@link Number}, and string {@code "1"} representations.
	 *
	 * @param s   the DB result row
	 * @param key the column key to look up
	 * @return the boolean value; {@code false} if the key is absent or zero
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private boolean boolVal(Map<String, Object> s, String key) {
		Object v = s.get(key);
		if (v == null)
			return false;
		if (v instanceof Boolean)
			return (Boolean) v;
		if (v instanceof Number)
			return ((Number) v).intValue() != 0;
		return "1".equals(v.toString());
	}
}
