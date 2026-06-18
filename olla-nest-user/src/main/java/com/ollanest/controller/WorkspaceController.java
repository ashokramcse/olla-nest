package com.ollanest.controller;

import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.WorkspaceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Workspace file system operations for the authenticated user.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The workspace feature lets permitted users point the AI at a local directory.
 * This controller provides the directory browsing and per-user workspace
 * configuration behind that feature, with strict path validation so it cannot
 * be turned into an arbitrary filesystem reader.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li><b>Security (HIGH-4):</b> all path inputs are validated against the user's
 * home directory and the application data directory to prevent path-traversal.</li>
 * <li>Hidden directories (names starting with {@code .}) are excluded so
 * sensitive config folders are never listed.</li>
 * <li>Browsing requires the {@code workspace:build} right — not every
 * authenticated user gets filesystem access.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration; HIGH-4 hardening
 * restricted browsing to the user home directory</li>
 * </ul>
 *
 * <pre>
 *   GET  /api/workspace/browse          — list subdirectories of a path
 *   POST /api/workspace/local-settings  — save workspace root + permission mode
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController extends BaseController {

	/** JDBC template for workspace preference persistence. */
	private final JdbcTemplate db;

	/** Business logic for workspace configuration and artifact handling. */
	private final WorkspaceService workspaceService;

	/** Used to write audit log entries for workspace configuration changes. */
	private final ChatService chatService;

	/**
	 * Base data directory; workspace directory is created as a subdirectory here.
	 */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/**
	 * Constructor-injects required dependencies.
	 *
	 * @param db               the JDBC template
	 * @param workspaceService the workspace configuration service
	 * @param chatService      the audit log helper
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public WorkspaceController(JdbcTemplate db, WorkspaceService workspaceService, ChatService chatService) {
		this.db = db;
		this.workspaceService = workspaceService;
		this.chatService = chatService;
	}

	/**
	 * Returns a directory listing for the requested path.
	 *
	 * <p>
	 * Only non-hidden subdirectories are included. If the requested path is outside
	 * the user's home directory or the app data directory, the response falls back
	 * to the user's home directory. The optional {@code create=1} parameter creates
	 * the directory if it does not exist.
	 *
	 * <p>
	 * Requires the {@code workspace:build} right (or admin role).
	 *
	 * @param path   optional absolute path to list; defaults to the user's home
	 *               directory
	 * @param create if {@code "1"}, the requested directory is created via
	 *               {@code mkdirs()}
	 * @param req    the current HTTP request (for auth and right check)
	 * @return 200 OK with {@code {current, parent, dirs, home, hostHome}}, or 403
	 *         if missing the right, or 400 on path error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: restrict browse to user home
	 *          directory (HIGH-4)
	 */
	@GetMapping("/browse")
	public ResponseEntity<Map<String, Object>> browse(@RequestParam(required = false) String path,
			@RequestParam(required = false) String create, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User browseUser = getUser(req);
		boolean hasBuildRight = "admin".equals(browseUser.role)
				|| (browseUser.rights != null && browseUser.rights.contains("workspace:build"));
		if (!hasBuildRight) {
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", "workspace:build right required"));
		}

		String userHome = System.getProperty("user.home");
		String defaultHome = userHome;
		// Ensure data/workspace exists
		File workspace = new File(dataDir, "workspace");
		workspace.mkdirs();

		String absDataDir = new File(dataDir).getAbsolutePath();
		try {
			String requestedPath = (path != null && !path.isBlank()) ? path : defaultHome;
			File resolved = new File(requestedPath).getCanonicalFile();

			// Guard: restrict to user home or app data dir to prevent path traversal
			if (!resolved.getAbsolutePath().startsWith(userHome)
					&& !resolved.getAbsolutePath().startsWith(absDataDir)) {
				resolved = new File(defaultHome).getCanonicalFile();
			}

			if ("1".equals(create)) {
				resolved.mkdirs();
			}
			if (!resolved.exists() || !resolved.isDirectory()) {
				resolved = new File(defaultHome).getCanonicalFile();
			}

			File[] entries = resolved.listFiles();
			List<Map<String, Object>> dirs = new ArrayList<>();
			if (entries != null) {
				Arrays.sort(entries, Comparator.comparing(File::getName));
				for (File e : entries) {
					// Exclude hidden directories to avoid exposing dotfiles/config
					if (e.isDirectory() && !e.getName().startsWith(".")) {
						Map<String, Object> d = new LinkedHashMap<>();
						d.put("name", e.getName());
						d.put("path", e.getAbsolutePath());
						dirs.add(d);
					}
				}
			}

			String parent = null;
			File parentFile = resolved.getParentFile();
			if (parentFile != null && !resolved.getAbsolutePath().equals(defaultHome)) {
				parent = parentFile.getAbsolutePath();
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("current", resolved.getAbsolutePath());
			result.put("parent", parent);
			result.put("dirs", dirs);
			result.put("home", defaultHome);
			// LOW-6 FIX: removed 'hostHome' — exposing the server JVM user's home path
			// leaks server filesystem layout to all workspace:build users unnecessarily.
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", e.getMessage()));
		}
	}

	/**
	 * Saves the user's local workspace root directory and permission mode.
	 *
	 * <p>
	 * Validates that the resolved absolute path is within the user's home directory
	 * or the app data directory. Creates the directory if it does not exist. If
	 * {@code workspaceRoot} is empty, the user's workspace preference row is
	 * deleted (resetting to the global default).
	 *
	 * @param body request body with {@code workspaceRoot} (String) and
	 *             {@code permissionMode} (String: {@code "default"} or
	 *             {@code "full"})
	 * @param req  the current HTTP request (for auth check)
	 * @return 200 OK with {@code {ok: true, workspace: map}}, or 400 if the path is
	 *         outside allowed directories
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/local-settings")
	public ResponseEntity<Map<String, Object>> saveLocalSettings(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = guardAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);

		String workspaceRootInput = body.get("workspaceRoot") != null ? body.get("workspaceRoot").toString().trim()
				: "";
		String permissionMode = workspaceService.normalizePermissionMode(
				body.get("permissionMode") != null ? body.get("permissionMode").toString() : "default");

		if (workspaceRootInput.isEmpty()) {
			db.update("DELETE FROM workspace_prefs WHERE user_id = ?", user.id);
			chatService.appendAudit(user.name, "workspace.local.clear", "Cleared local workspace folder", null);
			return ResponseEntity.ok(Map.of("ok", true, "workspace", workspaceService.workspaceForUser(user.id)));
		}

		String nextRoot = Paths.get(workspaceRootInput).toAbsolutePath().normalize().toString();
		String userHome2 = System.getProperty("user.home");
		String absDataDir2 = new File(dataDir).getAbsolutePath();
		if (!nextRoot.startsWith(userHome2) && !nextRoot.startsWith(absDataDir2)) {
			return ResponseEntity.status(400)
					.body(Map.of("ok", false, "error", "Workspace path must be within your home directory"));
		}

		try {
			new File(nextRoot).mkdirs();
		} catch (Exception e) {
			return ResponseEntity.status(400)
					.body(Map.of("ok", false, "error", "Cannot create folder: " + e.getMessage()));
		}

		db.update(
				"INSERT OR REPLACE INTO workspace_prefs "
						+ "(user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)",
				user.id, nextRoot, permissionMode, Instant.now().toString());
		chatService.appendAudit(user.name, "workspace.local.save", "Updated workspace folder to " + nextRoot,
				Map.of("permissionMode", permissionMode));
		return ResponseEntity.ok(Map.of("ok", true, "workspace", workspaceService.workspaceForUser(user.id)));
	}
}
