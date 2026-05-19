/**
 * @file src/routes/admin/settings.js
 * @description Admin settings routes: /api/admin/settings, /api/admin/departments.
 */

const fs = require("fs");
const path = require("path");
const { DEFAULT_WORKSPACE_ROOT } = require("../../config");
const { runBackup } = require("../../services/backup");

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, rows, one } = deps;
  const { requireAdmin } = deps;
  const { settingsState, setSetting, setting } = deps;
  const { cleanBaseUrl } = deps;
  const { appendAudit } = deps;

  // [POST] /api/admin/settings — Auth: requireAdmin — Purpose: update platform settings (router, Ollama URL, API keys, workspace)
  router.post("/", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      ["routerEnabled", "allowApiModels", "localOnlyDefault", "localWritesEnabled", "localPermissionMode", "apiModelProvider", "projectKnowledge",
        "anthropicEnabled", "anthropicApiKey", "anthropicBaseUrl",
        "openaiEnabled", "openaiApiKey", "openaiBaseUrl",
        "groqEnabled", "groqApiKey",
        "customEnabled", "customApiKey", "customBaseUrl", "customName",
      ].forEach((key) => {
        if (typeof req.body[key] !== "undefined") setSetting(db, key, req.body[key]);
      });
      // Router config
      if (typeof req.body.routerWeights !== "undefined") setSetting(db, "routerWeights", JSON.stringify(req.body.routerWeights));
      if (typeof req.body.sensitivePatterns !== "undefined") setSetting(db, "sensitivePatterns", JSON.stringify(req.body.sensitivePatterns));
      if (typeof req.body.localOnlyModes !== "undefined") setSetting(db, "localOnlyModes", JSON.stringify(req.body.localOnlyModes));
      if (typeof req.body.workspaceRoot !== "undefined") {
        const nextRoot = path.resolve(String(req.body.workspaceRoot || DEFAULT_WORKSPACE_ROOT));
        setSetting(db, "workspaceRoot", nextRoot);
        fs.mkdirSync(nextRoot, { recursive: true });
      }
      if (typeof req.body.ollamaUrl !== "undefined") {
        const nextUrl = cleanBaseUrl(req.body.ollamaUrl);
        if (!/^https?:\/\/[^ "]+$/.test(nextUrl)) return res.status(400).json({ error: "Ollama URL must start with http:// or https://" });
        setSetting(db, "ollamaUrl", nextUrl);
      }
      appendAudit(user.name, "admin.settings.save", "Updated system settings");
      res.json({ ok: true, settings: settingsState(db) });
    } finally {
      db.close();
    }
  });

  // [GET] /api/admin/departments — Auth: requireAdmin — Purpose: list all departments with their default rights
  router.get("/departments", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const depts = rows(db, "SELECT id, name FROM departments ORDER BY name");
      // load default rights per dept from settings
      const raw = setting(db, "deptDefaultRights", "{}");
      let deptRights = {};
      try { deptRights = JSON.parse(raw); } catch { deptRights = {}; }
      res.json({ departments: depts.map(d => ({ ...d, defaultRights: deptRights[d.id] || [] })) });
    } finally { db.close(); }
  });

  // [PATCH] /api/admin/departments/:id/rights — Auth: requireAdmin — Purpose: set default permission rights for a department
  router.patch("/departments/:id/rights", requireAdmin, (req, res) => {
    if (!req.headers["x-requested-with"]) return res.status(403).json({ error: "Forbidden: missing CSRF header" });
    const db = openSql();
    try {
      const { rights } = req.body;
      const raw = setting(db, "deptDefaultRights", "{}");
      let deptRights = {};
      try { deptRights = JSON.parse(raw); } catch { deptRights = {}; }
      deptRights[req.params.id] = Array.isArray(rights) ? rights : [];
      setSetting(db, "deptDefaultRights", JSON.stringify(deptRights));
      res.json({ ok: true });
    } finally { db.close(); }
  });

  // [POST] /api/admin/backup — Auth: requireAdmin — Purpose: trigger a manual SQLite backup
  router.post("/backup", requireAdmin, (req, res) => {
    const result = runBackup();
    res.json(result);
  });

  return router;
};
