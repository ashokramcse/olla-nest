/**
 * @file src/routes/workspace.js
 * @description Workspace routes: /api/workspace/browse, /api/workspace/local-settings.
 *
 * Cross-platform path mapping inside the container:
 *   /host-home/  ← bind-mount of ${HOME} from the Docker host
 *                   macOS  : /Users/username      → /host-home/
 *                   Linux  : /home/username        → /host-home/
 *                   Windows: C:\Users\username     → /host-home/  (Docker Desktop WSL2)
 *   /app/data/   ← named Docker volume, always writable (container storage)
 */

const fs = require("fs");
const path = require("path");
const { DATA_DIR } = require("../config");

const HOST_HOME = "/host-home"; /* bind-mounted from ${HOME} on the Docker host — works on macOS, Linux, Windows */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAuth, requireAdmin } = deps;
  const { workspaceForUser, normalizePermissionMode } = deps;
  const { appendAudit } = deps;

  // [GET] /api/workspace/browse — Auth: requireAuth — Purpose: browse host home dirs for workspace folder picker
  // Open to all authenticated users (not just admin) so any employee can pick their project folder.
  router.get("/browse", requireAuth, (req, res) => {
    const defaultHome = fs.existsSync(HOST_HOME) ? HOST_HOME : path.join(DATA_DIR, "workspace");
    fs.mkdirSync(path.join(DATA_DIR, "workspace"), { recursive: true });
    try {
      const requestedPath = String(req.query.path || defaultHome).trim();
      let resolved = path.resolve(requestedPath);
      if (req.query.create === "1") {
        fs.mkdirSync(resolved, { recursive: true });
      }
      if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
        resolved = defaultHome;
      }
      const entries = fs.readdirSync(resolved, { withFileTypes: true });
      const dirs = entries
        .filter((e) => e.isDirectory() && !e.name.startsWith("."))
        .sort((a, b) => a.name.localeCompare(b.name))
        .map((e) => ({ name: e.name, path: path.join(resolved, e.name) }));
      const parentPath = (resolved === path.parse(resolved).root || resolved === defaultHome) ? null : path.dirname(resolved);
      res.json({ current: resolved, parent: parentPath, dirs, home: defaultHome, hostHome: HOST_HOME });
    } catch (err) {
      res.status(400).json({ error: err.message });
    }
  });

  // [POST] /api/workspace/local-settings — Auth: requireAuth — Purpose: save or clear per-user workspace folder + permission mode
  router.post("/local-settings", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const workspaceRootInput = String(req.body.workspaceRoot || "").trim();
      const permissionMode = normalizePermissionMode(req.body.permissionMode);
      if (!workspaceRootInput) {
        db.prepare("DELETE FROM workspace_prefs WHERE user_id = ?").run(req.user.id);
        appendAudit(req.user.name, "workspace.local.clear", "Cleared local workspace folder");
        return res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
      }
      const nextRoot = path.resolve(workspaceRootInput);

      // The container can write to:
      //   1. /app/data/    — Docker named volume (always available, any OS)
      //   2. /host-home/   — ${HOME} bind-mount from the Docker host (macOS, Linux, Windows)
      // Any other absolute path is not accessible from inside the container.
      const DATA_DIR_ABS = path.resolve(DATA_DIR);
      if (!nextRoot.startsWith(DATA_DIR_ABS) && !nextRoot.startsWith(HOST_HOME)) {
        return res.status(400).json({
          error: `Path not accessible inside the container. Use your home folder via /host-home/ (maps to ~ on your OS) or /app/data/workspace/ for container storage.`,
        });
      }

      try {
        fs.mkdirSync(nextRoot, { recursive: true });
      } catch (mkdirErr) {
        return res.status(400).json({
          error: `Cannot create folder: ${mkdirErr.message}`,
        });
      }

      db.prepare("INSERT OR REPLACE INTO workspace_prefs (user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)").run(
        req.user.id, nextRoot, permissionMode, new Date().toISOString()
      );
      appendAudit(req.user.name, "workspace.local.save", `Updated workspace folder to ${nextRoot}`, { permissionMode });
      res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
    } finally {
      db.close();
    }
  });

  return router;
};
