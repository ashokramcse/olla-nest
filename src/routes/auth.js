/**
 * @file src/routes/auth.js
 * @description Auth routes: /api/auth/login, /api/auth/logout, /api/auth/me, /api/bootstrap.
 */

const bcrypt = require("bcryptjs");

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { requireAuth, sessionUser, setSession, parseCookies, sessions } = deps;
  const { publicUser, USER_SELECT } = deps;
  const { appendAudit, setSetting } = deps;
  const { loginAttempts, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS } = deps;
  const { DEFAULT_ADMIN_EMAIL, DEFAULT_ADMIN_PASSWORD } = deps;

  // [POST] /api/auth/login — Auth: public — Purpose: authenticate with email+password; sets HttpOnly session cookie
  router.post("/login", (req, res) => {
    // Rate limit by IP — stored in SQLite so all cluster workers share the same counter
    const ip = req.headers["x-forwarded-for"]?.split(",")[0]?.trim() || req.socket.remoteAddress || "unknown";
    const now = Date.now();

    const db = openSql();
    try {
      // Load or initialise the attempt row for this IP
      let attempt = db.prepare("SELECT count, reset_at FROM login_attempts WHERE ip = ?").get(ip);
      if (!attempt || now > attempt.reset_at) {
        // No record or window expired — start fresh
        db.prepare("INSERT OR REPLACE INTO login_attempts (ip, count, reset_at) VALUES (?, 0, ?)").run(ip, now + LOGIN_WINDOW_MS);
        attempt = { count: 0, reset_at: now + LOGIN_WINDOW_MS };
      }
      if (attempt.count >= LOGIN_MAX_ATTEMPTS) {
        const retryAfter = Math.ceil((attempt.reset_at - now) / 1000);
        return res.status(429).json({ error: `Too many login attempts. Try again in ${Math.ceil(retryAfter / 60)} minutes.` });
      }

      const { email, password } = req.body;
      if (!email || !password) return res.status(400).json({ error: "Email and password are required" });
      const row = one(db, `SELECT ${USER_SELECT}, password_hash FROM users WHERE email = ? AND active = 1`, email);
      if (!row || !row.password_hash || !bcrypt.compareSync(String(password || ""), row.password_hash)) {
        db.prepare("UPDATE login_attempts SET count = count + 1 WHERE ip = ?").run(ip);
        return res.status(401).json({ error: "Invalid email or password" });
      }
      // Successful login — reset attempt counter
      db.prepare("DELETE FROM login_attempts WHERE ip = ?").run(ip);
      const user = publicUser(row);
      setSetting(db, "activeUserId", user.id);
      setSession(res, user, req);
      appendAudit(user.name, "auth.login", "User signed in");
      res.json({ ok: true, user, redirectTo: user.role === "admin" ? "/admin" : "/app" });
    } finally {
      db.close();
    }
  });

  // [POST] /api/auth/logout — Auth: public (CSRF checked) — Purpose: invalidate session cookie
  router.post("/logout", (req, res) => {
    if (req.headers["x-requested-with"] !== "XMLHttpRequest") {
      return res.status(403).json({ error: "Forbidden" });
    }
    const token = parseCookies(req).olla_nest_session;
    if (token) sessions.delete(token);
    res.setHeader("Set-Cookie", "olla_nest_session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
    res.json({ ok: true });
  });

  // [GET] /api/auth/me — Auth: public — Purpose: check if the browser has a valid session; used on page load
  router.get("/me", (req, res) => {
    const user = sessionUser(req);
    res.json({ authenticated: Boolean(user), user });
  });

  return router;
};
