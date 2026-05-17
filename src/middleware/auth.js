/**
 * @file src/middleware/auth.js
 * @description Session management and auth middleware: sessions Map, parseCookies,
 * sessionUser, setSession, requireAuth, requireAdmin, hasRight.
 */

const crypto = require("crypto");

/**
 * In-memory session store.  Maps random 32-byte hex token → { user, expiresAt }.
 * Sessions survive server restarts only if the container is not restarted
 * (i.e. they are ephemeral).  12-hour TTL set in setSession().
 */
const sessions = new Map();

function parseCookies(req) {
  return Object.fromEntries(
    String(req.headers.cookie || "")
      .split(";")
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => {
        const index = part.indexOf("=");
        return [part.slice(0, index), decodeURIComponent(part.slice(index + 1))];
      })
  );
}

function sessionUser(req) {
  const token = parseCookies(req).olla_nest_session;
  if (!token) return null;
  const session = sessions.get(token);
  if (!session || session.expiresAt < Date.now()) {
    sessions.delete(token);
    return null;
  }
  return session.user;
}

/**
 * Express middleware that requires an authenticated admin session.
 * Also enforces the CSRF guard on state-changing requests: non-GET requests
 * must include the `X-Requested-With: XMLHttpRequest` header (set by every
 * api() call in the frontend).  This prevents cross-site form submissions.
 *
 * Attaches req.user = the session user object on success.
 */
function requireAdmin(req, res, next) {
  const user = sessionUser(req);
  if (!user) return res.status(401).json({ error: "Login required" });
  if (user.role !== "admin") return res.status(403).json({ error: "Admin access required" });
  // CSRF guard: non-GET requests from browsers must include this header
  if (req.method !== "GET" && !req.headers["x-requested-with"]) {
    return res.status(403).json({ error: "Forbidden: missing CSRF header" });
  }
  req.user = user;
  next();
}

/**
 * Express middleware that requires any authenticated session (any role).
 * Applies the same CSRF guard as requireAdmin on non-GET requests.
 * Attaches req.user on success.
 */
function requireAuth(req, res, next) {
  const user = sessionUser(req);
  if (!user) return res.status(401).json({ error: "Login required" });
  // CSRF guard on state-changing requests
  if (req.method !== "GET" && !req.headers["x-requested-with"]) {
    return res.status(403).json({ error: "Forbidden: missing CSRF header" });
  }
  req.user = user;
  next();
}

/**
 * Returns true if the user has a specific permission right.
 * Admins always return true regardless of their rights array.
 *
 * @param {object} user - publicUser() shaped object.
 * @param {string} right - Permission key to check (e.g. "chat:use").
 * @returns {boolean}
 */
function hasRight(user, right) {
  return user.role === "admin" || (user.rights || []).includes(right);
}

function setSession(res, user, req) {
  // Invalidate any existing session to prevent session fixation
  const existingCookies = Object.fromEntries(
    String(req?.headers?.cookie || "").split(";")
      .map(s => s.trim()).filter(Boolean)
      .map(s => { const i = s.indexOf("="); return [s.slice(0, i), decodeURIComponent(s.slice(i + 1))]; })
  );
  if (existingCookies.olla_nest_session) sessions.delete(existingCookies.olla_nest_session);

  const token = crypto.randomBytes(32).toString("hex");
  sessions.set(token, { user, expiresAt: Date.now() + 1000 * 60 * 60 * 12 });
  const isSecure = (req?.headers["x-forwarded-proto"] === "https") || req?.secure;
  const secureFlag = isSecure ? "; Secure" : "";
  res.setHeader("Set-Cookie", `olla_nest_session=${encodeURIComponent(token)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200${secureFlag}`);
}

module.exports = { sessions, parseCookies, sessionUser, setSession, requireAuth, requireAdmin, hasRight };
