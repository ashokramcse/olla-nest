/**
 * @file src/routes/admin/teams.js
 * @description Admin teams routes: /api/admin/teams (CRUD).
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, rows } = deps;
  const { requireAdmin } = deps;
  const { appendAudit } = deps;
  const { uid } = deps;

  // ─── Teams CRUD ───────────────────────────────────────────────────────────────
  // [GET] /api/admin/teams — Auth: requireAdmin — Purpose: list all teams
  router.get("/", requireAdmin, (req, res) => {
    const db = openSql();
    try { res.json({ teams: rows(db, "SELECT * FROM teams ORDER BY name") }); }
    finally { db.close(); }
  });

  // [POST] /api/admin/teams — Auth: requireAdmin — Purpose: create a new team (name must be unique)
  router.post("/", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const { name, description = "" } = req.body;
      if (!name || !name.trim()) return res.status(400).json({ error: "Team name is required" });
      const id = uid("team");
      db.prepare("INSERT INTO teams (id, name, description, created_at) VALUES (?, ?, ?, ?)").run(id, name.trim(), description.trim(), new Date().toISOString());
      appendAudit(req.user.name, "admin.team.create", `Created team: ${name.trim()}`);
      res.json({ ok: true, team: { id, name: name.trim(), description: description.trim() } });
    } catch (err) {
      res.status(400).json({ error: err.message.includes("UNIQUE") ? "A team with that name already exists" : err.message });
    } finally { db.close(); }
  });

  // [DELETE] /api/admin/teams/:id — Auth: requireAdmin — Purpose: delete a team by ID
  router.delete("/:id", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      db.prepare("DELETE FROM teams WHERE id = ?").run(req.params.id);
      appendAudit(req.user.name, "admin.team.delete", `Deleted team ${req.params.id}`);
      res.json({ ok: true });
    } finally { db.close(); }
  });

  return router;
};
