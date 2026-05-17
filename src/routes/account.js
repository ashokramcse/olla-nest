/**
 * @file src/routes/account.js
 * @description Account routes: /api/account/password, /api/account/profile, /api/account/usage.
 */

const bcrypt = require("bcryptjs");

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { requireAuth } = deps;
  const { publicUser, USER_SELECT } = deps;
  const { appendAudit } = deps;

  // [POST] /api/account/password — Auth: requireAuth — Purpose: self-service password change (requires current password)
  router.post("/password", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const { currentPassword, newPassword } = req.body;
      if (!newPassword || String(newPassword).length < 12) return res.status(400).json({ error: "New password must be at least 12 characters" });
      const row = one(db, `SELECT ${USER_SELECT}, password_hash FROM users WHERE id = ?`, req.user.id);
      if (!row || !bcrypt.compareSync(String(currentPassword || ""), row.password_hash || "")) {
        return res.status(401).json({ error: "Current password is incorrect" });
      }
      db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(bcrypt.hashSync(String(newPassword), 12), req.user.id);
      appendAudit(req.user.name, "account.password.change", "Changed own password");
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [PATCH] /api/account/profile — Auth: requireAuth — Purpose: self-service profile update (name, phone, etc.)
  router.patch("/profile", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const row = one(db, `SELECT ${USER_SELECT}, password_hash FROM users WHERE id = ?`, req.user.id);
      if (!row) return res.status(404).json({ error: "User not found" });
      const isEnterprise = (row.authProvider || row.auth_provider || "local") !== "local";

      // Fields anyone can edit
      const allowed = ["name", "phone", "avatar_initials"];
      // Extra fields only local-account users can change
      if (!isEnterprise) allowed.push("designation", "team", "branch");

      const updates = [];
      const vals = [];
      for (const field of allowed) {
        const camel = field.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
        if (typeof req.body[camel] !== "undefined") {
          updates.push(`${field} = ?`);
          vals.push(String(req.body[camel] || "").trim());
        }
      }
      if (updates.length === 0) return res.status(400).json({ error: "No updatable fields provided" });
      vals.push(req.user.id);
      db.prepare(`UPDATE users SET ${updates.join(", ")} WHERE id = ?`).run(...vals);
      const updated = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
      appendAudit(updated.name, "account.profile.update", "Updated own profile");
      res.json({ ok: true, user: updated });
    } finally {
      db.close();
    }
  });

  // [GET] /api/account/profile — Auth: requireAuth — Purpose: fetch current user's full profile with department name
  router.get("/profile", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const row = one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id);
      if (!row) return res.status(404).json({ error: "User not found" });
      const user = publicUser(row);
      const dept = row.departmentId ? one(db, "SELECT name FROM departments WHERE id = ?", row.departmentId) : null;
      res.json({ user, departmentName: dept?.name || "" });
    } finally {
      db.close();
    }
  });

  // [GET] /api/account/usage — Auth: requireAuth — Purpose: return today's and this month's token usage for the token bar UI
  router.get("/usage", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id);
      if (!user) return res.status(404).json({ error: "User not found" });

      const todayStart = new Date();
      todayStart.setHours(0, 0, 0, 0);
      const todayISO = todayStart.toISOString();

      const monthStart = new Date();
      monthStart.setDate(1); monthStart.setHours(0, 0, 0, 0);
      const monthISO = monthStart.toISOString();

      const todayRow = db.prepare(
        `SELECT COALESCE(SUM(m.tokens_used),0) AS total
         FROM chat_messages m
         JOIN chat_sessions s ON s.id = m.session_id
         WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?`
      ).get(req.user.id, todayISO);

      const monthRow = db.prepare(
        `SELECT COALESCE(SUM(m.tokens_used),0) AS total
         FROM chat_messages m
         JOIN chat_sessions s ON s.id = m.session_id
         WHERE s.user_id = ? AND m.role = 'assistant' AND m.created_at >= ?`
      ).get(req.user.id, monthISO);

      res.json({
        tokensUsedToday: todayRow?.total || 0,
        dailyTokenLimit: user.dailyTokenLimit || 50000,
        tokensUsedMonth: monthRow?.total || 0,
        monthlyTokenLimit: user.monthlyTokenLimit || 1000000,
      });
    } finally {
      db.close();
    }
  });

  return router;
};
