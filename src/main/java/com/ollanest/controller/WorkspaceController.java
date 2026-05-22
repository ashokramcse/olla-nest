package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController extends BaseController {

    private final JdbcTemplate db;
    private final WorkspaceService workspaceService;
    private final ChatService chatService;

    @Value("${app.data-dir:./data}")
    private String dataDir;

    public WorkspaceController(JdbcTemplate db, WorkspaceService workspaceService, ChatService chatService) {
        this.db = db;
        this.workspaceService = workspaceService;
        this.chatService = chatService;
    }

    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browse(@RequestParam(required = false) String path,
                                                       @RequestParam(required = false) String create,
                                                       HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User browseUser = getUser(req);
        boolean hasBuildRight = "admin".equals(browseUser.role)
            || (browseUser.rights != null && browseUser.rights.contains("workspace:build"));
        if (!hasBuildRight) return ResponseEntity.status(403).body(Map.of("error", "workspace:build right required"));

        // Use the user's real home directory
        String userHome = System.getProperty("user.home");
        String defaultHome = userHome;
        // Ensure data/workspace exists
        File workspace = new File(dataDir, "workspace");
        workspace.mkdirs();

        String absDataDir = new File(dataDir).getAbsolutePath();
        try {
            String requestedPath = (path != null && !path.isBlank()) ? path : defaultHome;
            File resolved = new File(requestedPath).getCanonicalFile();

            // Guard: restrict to user home or app data dir
            if (!resolved.getAbsolutePath().startsWith(userHome) && !resolved.getAbsolutePath().startsWith(absDataDir)) {
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
            result.put("hostHome", userHome);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/local-settings")
    public ResponseEntity<Map<String, Object>> saveLocalSettings(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);

        String workspaceRootInput = body.get("workspaceRoot") != null ? body.get("workspaceRoot").toString().trim() : "";
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
            return ResponseEntity.status(400).body(Map.of("error", "Workspace path must be within your home directory"));
        }

        try {
            new File(nextRoot).mkdirs();
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Cannot create folder: " + e.getMessage()));
        }

        db.update("INSERT OR REPLACE INTO workspace_prefs (user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)",
            user.id, nextRoot, permissionMode, Instant.now().toString());
        chatService.appendAudit(user.name, "workspace.local.save", "Updated workspace folder to " + nextRoot, Map.of("permissionMode", permissionMode));
        return ResponseEntity.ok(Map.of("ok", true, "workspace", workspaceService.workspaceForUser(user.id)));
    }
}
