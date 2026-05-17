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
    // Rate limit by IP
    const ip = req.headers["x-forwarded-for"]?.split(",")[0]?.trim() || req.socket.remoteAddress || "unknown";
    const now = Date.now();
    const attempt = loginAttempts.get(ip) || { count: 0, resetAt: now + LOGIN_WINDOW_MS };
    if (now > attempt.resetAt) { attempt.count = 0; attempt.resetAt = now + LOGIN_WINDOW_MS; }
    if (attempt.count >= LOGIN_MAX_ATTEMPTS) {
      const retryAfter = Math.ceil((attempt.resetAt - now) / 1000);
      return res.status(429).json({ error: `Too many login attempts. Try again in ${Math.ceil(retryAfter / 60)} minutes.` });
    }

    const db = openSql();
    try {
      const { email, password } = req.body;
      if (!email || !password) return res.status(400).json({ error: "Email and password are required" });
      const row = one(db, `SELECT ${USER_SELECT}, password_hash FROM users WHERE email = ? AND active = 1`, email);
      if (!row || !row.password_hash || !bcrypt.compareSync(String(password || ""), row.password_hash)) {
        attempt.count++;
        loginAttempts.set(ip, attempt);
        return res.status(401).json({ error: "Invalid email or password" });
      }
      // Successful login — reset attempt counter
      loginAttempts.delete(ip);
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
