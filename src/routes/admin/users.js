/**
 * @file src/routes/admin/users.js
 * @description Admin user routes: /api/admin/users, /api/admin/users/:id, /api/admin/users/:id/reset-password,
 * /api/admin/users/:id/effective-access, /api/admin/users/:id/overrides, /api/admin/overrides/:id,
 * /api/admin/sessions/active, /api/admin/sessions/user/:userId.
 */

const bcrypt = require("bcryptjs");

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { requireAdmin } = deps;
  const { publicUser, USER_SELECT, effectiveAccess, userOverrides } = deps;
  const { appendAudit } = deps;
  const { uid } = deps;
  const { sessions } = deps;
  const { DEFAULT_USER_PASSWORD } = deps;

  // [GET] /api/admin/sessions/active — Auth: requireAdmin — Purpose: list all active DB sessions (for security panel)
  router.get("/sessions/active", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const rows = db.prepare(
        `SELECT s.token, s.user_id, s.expires_at, u.name, u.email, u.role
         FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.expires_at > datetime('now') ORDER BY s.expires_at DESC`
      ).all();
      const list = rows.map(r => ({
        userId: r.user_id,
        name: r.name,
        email: r.email,
        role: r.role,
        expiresAt: r.expires_at,
        token: r.token.slice(0, 8) + "…",
      }));
      res.json({ sessions: list });
    } finally { db.close(); }
  });

  // [DELETE] /api/admin/sessions/user/:userId — Auth: requireAdmin — Purpose: force-logout a specific user
  router.delete("/sessions/user/:userId", requireAdmin, (req, res) => {
    const { userId } = req.params;
    const db = openSql();
    try {
      const result = db.prepare("DELETE FROM sessions WHERE user_id = ?").run(userId);
      res.json({ ok: true, cleared: result.changes });
    } finally { db.close(); }
  });

  // [GET] /api/admin/users — Auth: requireAdmin — Purpose: paginated user list with optional search
  router.get("/", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const page = Math.max(1, parseInt(req.query.page) || 1);
      const limit = Math.min(100, parseInt(req.query.limit) || 25);
      const search = req.query.search ? `%${req.query.search}%` : null;
      const offset = (page - 1) * limit;
      const where = search ? "WHERE name LIKE ? OR email LIKE ?" : "";
      const params = search ? [search, search] : [];
      const total = db.prepare(`SELECT COUNT(*) as n FROM users ${where}`).get(...params).n;
      const users = db.prepare(`SELECT ${USER_SELECT} FROM users ${where} ORDER BY role, name LIMIT ? OFFSET ?`).all(...params, limit, offset);
      res.json({ users: users.map(publicUser), total, page, limit, pages: Math.ceil(total / limit) });
    } finally { db.close(); }
  });

  // [POST] /api/admin/users — Auth: requireAdmin — Purpose: create a new user account; returns credentials for invite modal
  router.post("/", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const {
        name, email, role = "user", departmentId = "dept-general", rights = ["chat:use"], password,
        employeeId = "", designation = "", team = "", branch = "", manager = "", organization = "Olla Nest",
        aiAccessTier = "standard", dailyTokenLimit = 50000, monthlyTokenLimit = 1000000,
        gpuQuotaMinutes = 120, vramLimitMb = 8192, concurrentModelLimit = 1,
        apiRateLimitPerMinute = 30, maxContextSize = 8192, mfaEnabled = false,
        securityRiskScore = 10, accessStatus = "active", accessExpiresAt = "",
      } = req.body;
      if (!name || !email) return res.status(400).json({ error: "Name and email are required" });
      const id = uid("u");
      const passwordHash = bcrypt.hashSync(String(password || DEFAULT_USER_PASSWORD), 12);
      db.prepare(`INSERT INTO users
        (id, name, email, password_hash, role, rights, department_id, active,
         employee_id, designation, team, branch, manager, organization, ai_access_tier,
         daily_token_limit, monthly_token_limit, gpu_quota_minutes, vram_limit_mb,
         concurrent_model_limit, api_rate_limit_per_minute, max_context_size,
         mfa_enabled, security_risk_score, access_status, access_expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
        id,
        name,
        email,
        passwordHash,
        role,
        JSON.stringify(rights),
        departmentId,
        employeeId,
        designation,
        team,
        branch,
        manager,
        organization,
        aiAccessTier,
        Number(dailyTokenLimit),
        Number(monthlyTokenLimit),
        Number(gpuQuotaMinutes),
        Number(vramLimitMb),
        Number(concurrentModelLimit),
        Number(apiRateLimitPerMinute),
        Number(maxContextSize),
        mfaEnabled ? 1 : 0,
        Number(securityRiskScore),
        accessStatus,
        accessExpiresAt
      );
      db.prepare("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, ?)").run(id, "group-all");
      appendAudit(req.user.name, "admin.user.create", `Created user ${email}`);
      const createdUser = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, id));
      // Return plaintext password only on creation so admin can share credentials
      const plainPassword = String(password || DEFAULT_USER_PASSWORD);
      res.json({ ok: true, user: createdUser, credentials: { email, password: plainPassword, loginUrl: "/login" } });
    } catch (error) {
      res.status(400).json({ error: error.message });
    } finally {
      db.close();
    }
  });

  // [PATCH] /api/admin/users/:id — Auth: requireAdmin — Purpose: update any user field (name, role, rights, quotas, status)
  router.patch("/:id", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const existing = one(db, "SELECT id, role FROM users WHERE id = ?", req.params.id);
      if (!existing) return res.status(404).json({ error: "User not found" });
      const { name, email, role, departmentId, active, rights } = req.body;
      if (typeof active !== "undefined" && !active && existing.role === "admin") {
        return res.status(400).json({ error: "Admin accounts cannot be deactivated." });
      }
      if (typeof name !== "undefined") db.prepare("UPDATE users SET name = ? WHERE id = ?").run(name, req.params.id);
      if (typeof email !== "undefined") db.prepare("UPDATE users SET email = ? WHERE id = ?").run(email, req.params.id);
      if (typeof role !== "undefined") {
        if (!["admin", "user"].includes(role)) return res.status(400).json({ error: "Invalid role. Must be 'admin' or 'user'." });
        db.prepare("UPDATE users SET role = ? WHERE id = ?").run(role, req.params.id);
      }
      if (typeof departmentId !== "undefined") db.prepare("UPDATE users SET department_id = ? WHERE id = ?").run(departmentId, req.params.id);
      if (typeof active !== "undefined") db.prepare("UPDATE users SET active = ? WHERE id = ?").run(active ? 1 : 0, req.params.id);
      if (Array.isArray(rights)) db.prepare("UPDATE users SET rights = ? WHERE id = ?").run(JSON.stringify(rights), req.params.id);
      const mapped = {
        employeeId: "employee_id",
        designation: "designation",
        team: "team",
        branch: "branch",
        manager: "manager",
        organization: "organization",
        aiAccessTier: "ai_access_tier",
        dailyTokenLimit: "daily_token_limit",
        monthlyTokenLimit: "monthly_token_limit",
        gpuQuotaMinutes: "gpu_quota_minutes",
        vramLimitMb: "vram_limit_mb",
        concurrentModelLimit: "concurrent_model_limit",
        apiRateLimitPerMinute: "api_rate_limit_per_minute",
        maxContextSize: "max_context_size",
        mfaEnabled: "mfa_enabled",
        securityRiskScore: "security_risk_score",
        accessStatus: "access_status",
        accessExpiresAt: "access_expires_at",
      };
      for (const [bodyKey, column] of Object.entries(mapped)) {
        if (typeof req.body[bodyKey] === "undefined") continue;
        const value = bodyKey === "mfaEnabled" ? (req.body[bodyKey] ? 1 : 0) : req.body[bodyKey];
        db.prepare(`UPDATE users SET ${column} = ? WHERE id = ?`).run(value, req.params.id);
      }
      appendAudit(req.user.name, "admin.user.update", `Updated user ${req.params.id}`);
      res.json({ ok: true, user: publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.params.id)) });
    } catch (error) {
      res.status(400).json({ error: error.message });
    } finally {
      db.close();
    }
  });

  // [POST] /api/admin/users/:id/reset-password — Auth: requireAdmin — Purpose: admin-forced password reset (captcha in UI)
  router.post("/:id/reset-password", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const newPassword = String(req.body.password || DEFAULT_USER_PASSWORD);
      if (newPassword.length < 12) return res.status(400).json({ error: "Password must be at least 12 characters" });
      const result = db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(bcrypt.hashSync(newPassword, 12), req.params.id);
      if (result.changes === 0) return res.status(404).json({ error: "User not found" });
      appendAudit(req.user.name, "admin.user.reset_password", `Reset password for ${req.params.id}`);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [GET] /api/admin/users/:id/effective-access — Auth: requireAdmin — Purpose: compute merged permissions + allowed models for a user
  router.get("/:id/effective-access", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.params.id));
      if (!user) return res.status(404).json({ error: "User not found" });
      res.json({ ok: true, user, effectiveAccess: effectiveAccess(db, user), overrides: userOverrides(db, user.id) });
    } finally {
      db.close();
    }
  });

  // [POST] /api/admin/users/:id/overrides — Auth: requireAdmin — Purpose: add an allow/deny permission override (optionally time-limited)
  router.post("/:id/overrides", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.params.id));
      if (!user) return res.status(404).json({ error: "User not found" });
      const permissionKey = String(req.body.permissionKey || "").trim();
      const effect = String(req.body.effect || "allow");
      if (!permissionKey) return res.status(400).json({ error: "Permission is required" });
      if (!["allow", "deny"].includes(effect)) return res.status(400).json({ error: "Effect must be allow or deny" });
      db.prepare("INSERT INTO user_overrides (id, user_id, permission_key, model_id, effect, reason, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").run(
        uid("override"),
        user.id,
        permissionKey,
        req.body.modelId || null,
        effect,
        req.body.reason || "",
        req.body.expiresAt || "",
        new Date().toISOString()
      );
      appendAudit(req.user.name, "admin.access.override", `${effect.toUpperCase()} ${permissionKey} for ${user.email}`);
      res.json({ ok: true, effectiveAccess: effectiveAccess(db, user), overrides: userOverrides(db, user.id) });
    } finally {
      db.close();
    }
  });

  return router;
};
