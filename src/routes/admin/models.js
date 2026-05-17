/**
 * @file src/routes/admin/models.js
 * @description Admin model routes: /api/admin/models/:id/governance, /api/admin/ollama/ping.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { requireAdmin } = deps;
  const { parseModel } = deps;
  const { appendAudit } = deps;
  const { ollamaUrl, cleanBaseUrl } = deps;

  // [GET] /api/admin/ollama/ping — Auth: requireAdmin — Purpose: test raw Ollama connectivity; returns URL tried + model count
  router.get("/ollama/ping", requireAdmin, async (req, res) => {
    const db = openSql();
    const url = ollamaUrl(db);
    db.close();
    const controller = new AbortController();
    const t = setTimeout(() => controller.abort(), 10000);
    try {
      const r = await fetch(`${url}/api/tags`, { signal: controller.signal });
      clearTimeout(t);
      const data = await r.json();
      res.json({ ok: r.ok, url, status: r.status, modelCount: (data.models || []).length, models: (data.models || []).map(m => m.name) });
    } catch (err) {
      clearTimeout(t);
      res.json({ ok: false, url, error: err.message });
    }
  });

  // [PATCH] /api/admin/models/:id/governance — Auth: requireAdmin — Purpose: update model governance fields (tier, GPU, sensitivity)
  router.patch("/:id/governance", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const model = one(db, "SELECT id FROM models WHERE id = ?", req.params.id);
      if (!model) return res.status(404).json({ error: "Model not found" });
      const fields = {
        status: "status",
        governanceTier: "governance_tier",
        resourceTier: "resource_tier",
        gpuRequired: "gpu_required",
        maxConcurrency: "max_concurrency",
        maxContextSize: "max_context_size",
        externalCostTier: "external_cost_tier",
        sensitiveAllowed: "sensitive_allowed",
      };
      for (const [bodyKey, column] of Object.entries(fields)) {
        if (typeof req.body[bodyKey] === "undefined") continue;
        const value = ["gpuRequired", "sensitiveAllowed"].includes(bodyKey) ? (req.body[bodyKey] ? 1 : 0) : req.body[bodyKey];
        db.prepare(`UPDATE models SET ${column} = ? WHERE id = ?`).run(value, req.params.id);
      }
      appendAudit(req.user.name, "admin.model.governance", `Updated governance for ${req.params.id}`);
      res.json({ ok: true, model: parseModel(one(db, "SELECT * FROM models WHERE id = ?", req.params.id)) });
    } finally {
      db.close();
    }
  });

  return router;
};
