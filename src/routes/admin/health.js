/**
 * @file src/routes/admin/health.js
 * @description Admin health endpoint: GET /api/admin/health
 * Returns system metrics, DB counts, and uptime.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAdmin } = deps;
  const { getSnapshot } = require("../../services/monitor");

  // [GET] /api/admin/health — Auth: requireAdmin — Purpose: system health snapshot
  router.get("/", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const userCount = db.prepare("SELECT COUNT(*) as n FROM users").get().n;
      const sessionCount = db.prepare("SELECT COUNT(*) as n FROM sessions WHERE expires_at > datetime('now')").get().n;
      const modelCount = db.prepare("SELECT COUNT(*) as n FROM models WHERE status = 'available'").get().n;
      res.json({
        status: "ok",
        ...getSnapshot(),
        db: { userCount, sessionCount, modelCount },
      });
    } finally {
      db.close();
    }
  });

  return router;
};
