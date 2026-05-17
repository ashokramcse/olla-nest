/**
 * @file src/routes/admin/overrides.js
 * @description Admin overrides route: /api/admin/overrides/:id (DELETE).
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAdmin } = deps;
  const { appendAudit } = deps;

  // [DELETE] /api/admin/overrides/:id — Auth: requireAdmin — Purpose: remove a per-user permission override
  router.delete("/:id", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const result = db.prepare("DELETE FROM user_overrides WHERE id = ?").run(req.params.id);
      if (result.changes === 0) return res.status(404).json({ error: "Override not found" });
      appendAudit(req.user.name, "admin.access.override.delete", `Deleted override ${req.params.id}`);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  return router;
};
