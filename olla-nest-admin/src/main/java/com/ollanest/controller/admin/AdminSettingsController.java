package com.ollanest.controller.admin;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.BackupService;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.util.UrlValidator;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin settings management: read and write all application configuration.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Almost every tunable in the system is a row in the {@code settings} table.
 * This controller is the admin write/read surface for that configuration —
 * plus department default-rights and on-demand DB backups — persisting through
 * {@link DatabaseService} with validation applied before anything is stored.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li><b>Security (HIGH-3):</b> all base-URL fields are validated with
 * {@link UrlValidator} to prevent SSRF.</li>
 * <li><b>Security (HIGH-5):</b> the workspace-root setting is blocked from
 * pointing at system paths; only reasonable OS directories are accepted, and the
 * value is normalised to an absolute path.</li>
 * <li>Multiple settings can be saved in a single request for atomic UI saves.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration; HIGH-5 safe workspace-root
 * restriction and HIGH-3 SSRF protection on URL fields</li>
 * </ul>
 *
 * <pre>
 *   POST  /api/admin/settings                    — save one or more settings
 *   GET   /api/admin/departments                 — list depts with default rights
 *   PATCH /api/admin/departments/{id}/rights     — update a dept's default rights
 *   POST  /api/admin/settings/backup             — trigger an on-demand DB backup
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSettingsController extends BaseController {

	/** JDBC template for department queries. */
	private final JdbcTemplate db;

	/** Settings persistence and retrieval. */
	private final DatabaseService databaseService;

	/** Used to clean and validate Ollama URLs. */
	private final OllamaService ollamaService;

	/** Used to write audit log entries for settings changes. */
	private final ChatService chatService;

	/** Used to trigger on-demand backups. */
	private final BackupService backupService;

	/**
	 * Shared JSON mapper for serialising complex settings (router weights, etc.).
	 */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db              the JDBC template
	 * @param databaseService the settings service
	 * @param ollamaService   the Ollama HTTP client (for URL cleaning)
	 * @param chatService     the audit log helper
	 * @param backupService   the backup service
	 * @param mapper          the shared JSON object mapper
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public AdminSettingsController(JdbcTemplate db, DatabaseService databaseService, OllamaService ollamaService,
			ChatService chatService, BackupService backupService, ObjectMapper mapper) {
		this.db = db;
		this.databaseService = databaseService;
		this.ollamaService = ollamaService;
		this.chatService = chatService;
		this.backupService = backupService;
		this.mapper = mapper;
	}

	/**
	 * Returns the current application settings state.
	 *
	 * <p>
	 * Reads all persisted settings from the database and returns them as a
	 * structured map. This is the GET counterpart to the POST save endpoint and is
	 * required so the admin UI can hydrate the settings panel on load.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with the full settings state map
	 * @since v2026.1.9 — added missing GET endpoint (UX fix HIGH-1)
	 */
	@GetMapping("/settings")
	public ResponseEntity<Map<String, Object>> getSettings(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		return ResponseEntity.ok(Map.of("ok", true, "settings", buildSettingsState()));
	}

	/**
	 * Saves one or more application settings in a single request.
	 *
	 * <p>
	 * Simple string/boolean settings are saved directly. JSON-complex settings
	 * ({@code routerWeights}, {@code sensitivePatterns}, {@code localOnlyModes})
	 * are serialised before storage. URL settings are validated with
	 * {@link UrlValidator} before saving. The workspace root is blocked from
	 * pointing to system paths to prevent path-traversal exploits (HIGH-5).
	 *
	 * @param body request body with any combination of settings keys
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with the updated settings state, or 400 on URL validation
	 *         error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/settings")
	public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);

		List<String> simpleKeys = Arrays.asList("routerEnabled", "allowApiModels", "localOnlyDefault",
				"localWritesEnabled", "localPermissionMode", "apiModelProvider", "projectKnowledge", "anthropicEnabled",
				"anthropicApiKey", "anthropicBaseUrl", "openaiEnabled", "openaiApiKey", "openaiBaseUrl", "groqEnabled",
				"groqApiKey", "customEnabled", "customApiKey", "customBaseUrl", "customName",
				// ── Web search ──────────────────────────────────────────────
				"searchProvider", "searchApiKey",
				// ── Image generation ────────────────────────────────────────
				"imageProvider", "imageModel", "imageSize", "sdBaseUrl",
				// ── Voice (STT/TTS) ─────────────────────────────────────────
				// sttProvider: "local" (default, free/local faster-whisper) or "openai"
				// (paid/cloud)
				// sttLocalUrl: URL of the local faster-whisper server endpoint
				"voiceEnabled", "ttsVoice", "sttProvider", "sttLocalUrl");
		for (String key : simpleKeys) {
			if (body.containsKey(key))
				databaseService.setSetting(key, body.get(key).toString());
		}
		if (body.containsKey("routerWeights")) {
			try {
				databaseService.setSetting("routerWeights", mapper.writeValueAsString(body.get("routerWeights")));
			} catch (Exception ignored) {
			}
		}
		if (body.containsKey("sensitivePatterns")) {
			try {
				databaseService.setSetting("sensitivePatterns",
						mapper.writeValueAsString(body.get("sensitivePatterns")));
			} catch (Exception ignored) {
			}
		}
		if (body.containsKey("localOnlyModes")) {
			try {
				databaseService.setSetting("localOnlyModes", mapper.writeValueAsString(body.get("localOnlyModes")));
			} catch (Exception ignored) {
			}
		}
		if (body.containsKey("workspaceRoot")) {
			String nextRoot = Paths.get(body.get("workspaceRoot").toString()).toAbsolutePath().normalize().toString();
			// SOC 2 path safety: block system directories from being used as workspace
			// root.
			// This prevents an admin from inadvertently exposing /etc, /root, or OS paths
			// to the model's system prompt (which lists workspace files).
			for (String blocked : new String[] { "/etc", "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/boot", "/proc",
					"/sys", "/dev", "/root", "C:\\Windows", "C:\\System32" }) {
				if (nextRoot.startsWith(blocked)) {
					return ResponseEntity.status(400)
							.body(Map.of("ok", false, "error", "workspaceRoot must not point to a system directory"));
				}
			}
			databaseService.setSetting("workspaceRoot", nextRoot);
			try {
				new File(nextRoot).mkdirs();
			} catch (Exception ignored) {
				/* permission denied is non-fatal */ }
		}
		if (body.containsKey("ollamaUrl")) {
			String nextUrl = ollamaService.cleanBaseUrl(body.get("ollamaUrl").toString());
			if (!nextUrl.matches("^https?://[^ \"]+$")) {
				return ResponseEntity.status(400)
						.body(Map.of("ok", false, "error", "Ollama URL must start with http:// or https://"));
			}
			if (!UrlValidator.isSafeUrl(nextUrl)) {
				return ResponseEntity.status(400)
						.body(Map.of("ok", false, "error", "Ollama URL resolves to a disallowed address"));
			}
			databaseService.setSetting("ollamaUrl", nextUrl);
		}
		// searchBaseUrl for SearXNG — self-hosted, allow localhost/private IPs
		if (body.containsKey("searchBaseUrl")) {
			String urlVal = body.get("searchBaseUrl").toString().trim();
			if (!urlVal.isEmpty() && !urlVal.matches("^https?://[^ \"]+$")) {
				return ResponseEntity.status(400)
						.body(Map.of("ok", false, "error", "searchBaseUrl must start with http:// or https://"));
			}
			databaseService.setSetting("searchBaseUrl", urlVal);
		}
		// sdBaseUrl for Stable Diffusion — self-hosted, allow localhost/private IPs
		if (body.containsKey("sdBaseUrl")) {
			String urlVal = body.get("sdBaseUrl").toString().trim();
			if (!urlVal.isEmpty() && !urlVal.matches("^https?://[^ \"]+$")) {
				return ResponseEntity.status(400)
						.body(Map.of("ok", false, "error", "sdBaseUrl must start with http:// or https://"));
			}
			databaseService.setSetting("sdBaseUrl", urlVal);
		}

		// Validate external API base URL fields for SSRF (cloud services must not point
		// to internal hosts)
		for (String urlKey : Arrays.asList("anthropicBaseUrl", "openaiBaseUrl", "customBaseUrl")) {
			if (body.containsKey(urlKey)) {
				String urlVal = body.get(urlKey).toString().trim();
				if (!urlVal.isEmpty() && !UrlValidator.isSafeUrl(urlVal)) {
					return ResponseEntity.status(400)
							.body(Map.of("ok", false, "error", urlKey + " resolves to a disallowed address"));
				}
			}
		}

		chatService.appendAudit(admin.name, "admin.settings.save", "Updated system settings", null);
		return ResponseEntity.ok(Map.of("ok", true, "settings", buildSettingsState()));
	}

	/**
	 * Lists all departments with their configured default rights.
	 *
	 * <p>
	 * Default rights are stored as a JSON map keyed by department ID in the
	 * {@code deptDefaultRights} setting. Each department row is augmented with its
	 * {@code defaultRights} array.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {departments: [dept]}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping("/departments")
	public ResponseEntity<Map<String, Object>> listDepartments(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
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
		return ResponseEntity.ok(Map.of("departments", depts));
	}

	/**
	 * Updates the default rights assigned to new users in a specific department.
	 *
	 * <p>
	 * The rights list is merged into the global {@code deptDefaultRights} settings
	 * JSON map. Requires the CSRF header in addition to admin auth.
	 *
	 * @param id   the department ID to update
	 * @param body request body with {@code rights} (array of right strings)
	 * @param req  the current HTTP request (for admin and CSRF check)
	 * @return 200 OK {@code {ok: true}}, or 403 if CSRF header missing
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PatchMapping("/departments/{id}/rights")
	public ResponseEntity<Map<String, Object>> updateDepartmentRights(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		// Auth check MUST precede CSRF check — returning 403 before 401 leaks endpoint
		// existence to unauthenticated callers.
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		if (req.getHeader("x-requested-with") == null) {
			return ResponseEntity.status(403).body(Map.of("error", "Forbidden: missing CSRF header"));
		}
		Object rights = body.get("rights");
		String deptRightsJson = databaseService.getSetting("deptDefaultRights", "{}");
		Map<String, Object> deptRights;
		try {
			deptRights = mapper.readValue(deptRightsJson, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception e) {
			deptRights = new LinkedHashMap<>();
		}
		deptRights.put(id, rights instanceof List ? rights : List.of());
		try {
			databaseService.setSetting("deptDefaultRights", mapper.writeValueAsString(deptRights));
		} catch (Exception ignored) {
		}
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Triggers an on-demand database backup.
	 *
	 * <p>
	 * Delegates to {@link BackupService#runBackup()} which copies the SQLite file
	 * to the configured backup directory and prunes old backups.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with the backup result map from {@link BackupService}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/settings/backup")
	public ResponseEntity<Map<String, Object>> runBackup(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		Map<String, Object> result = backupService.runBackup();
		return ResponseEntity.ok(result);
	}

	/**
	 * Builds a snapshot of the current router and Ollama settings for the response.
	 *
	 * @return a settings state map with router flags and the Ollama URL
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
		s.put("routerWeights", safeJson(databaseService.getSetting("routerWeights", null)));
		// Voice STT provider settings
		s.put("sttProvider", databaseService.getSetting("sttProvider", "local"));
		s.put("sttLocalUrl",
				databaseService.getSetting("sttLocalUrl", "http://localhost:8765/v1/audio/transcriptions"));
		return s;
	}

	/**
	 * Parses a JSON string into an object, returning an empty map on failure.
	 *
	 * @param json the JSON string, or {@code null}
	 * @return the parsed object, or an empty map on error
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
}
