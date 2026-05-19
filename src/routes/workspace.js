/**
 * @file src/routes/workspace.js
 * @description Workspace routes: /api/workspace/browse, /api/workspace/local-settings.
 */

const fs = require("fs");
const path = require("path");
const { DATA_DIR } = require("../config");

const MAC_HOME = "/mac-home"; /* bind-mounted from ${HOME} on the host */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAuth, requireAdmin } = deps;
  const { workspaceForUser, normalizePermissionMode } = deps;
  const { appendAudit } = deps;

  // [GET] /api/workspace/browse — Auth: requireAdmin — Purpose: browse host filesystem dirs for workspace folder picker
  router.get("/browse", requireAdmin, (req, res) => {
    /* Prefer Mac home if mounted, else fall back to container workspace */
    const defaultHome = fs.existsSync(MAC_HOME) ? MAC_HOME : path.join(DATA_DIR, "workspace");
    fs.mkdirSync(path.join(DATA_DIR, "workspace"), { recursive: true });
    let resolved;
    try {
      const requestedPath = String(req.query.path || defaultHome).trim();
      resolved = path.resolve(requestedPath);
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
      res.json({ current: resolved, parent: parentPath, dirs, home: defaultHome, macHome: MAC_HOME });
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

      // Guard: the container runs as non-root appuser and can only write inside /app/data/.
      // Host paths like /Users/... are not writable from inside the container.
      const DATA_DIR_ABS = path.resolve(DATA_DIR);
      if (!nextRoot.startsWith(DATA_DIR_ABS) && !nextRoot.startsWith("/mac-home")) {
        return res.status(400).json({
          error: `Path not writable inside the container. Use /app/data/workspace/your-project — e.g. /app/data/workspace/my-app`,
        });
      }

      try {
        fs.mkdirSync(nextRoot, { recursive: true });
      } catch (mkdirErr) {
        return res.status(400).json({
          error: `Cannot create folder: ${mkdirErr.message}. Use /app/data/workspace/your-project`,
        });
      }

      db.prepare("INSERT OR REPLACE INTO workspace_prefs (user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)").run(
        req.user.id, nextRoot, permissionMode, new Date().toISOString()
      );
      appendAudit(req.user.name, "workspace.local.save", `Updated local workspace folder to ${nextRoot}`, { permissionMode });
      res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
    } finally {
      db.close();
    }
  });

  return router;
};
