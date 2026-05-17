/**
 * @file src/middleware/auth.js
 * @description Session management and auth middleware: SQLite-persisted sessions,
 * parseCookies, sessionUser, setSession, requireAuth, requireAdmin, hasRight.
 */

const crypto = require("crypto");
const { openSql } = require("../db/index");
const { USER_SELECT, publicUser } = require("../models/user");

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
  const db = openSql();
  try {
    const row = db.prepare(
      "SELECT s.user_id, u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ? AND s.expires_at > datetime('now')"
    ).get(token);
    if (!row) return null;
    // Build a user-like row from the join result
    return publicUser(row);
  } finally {
    db.close();
  }
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
  if (existingCookies.olla_nest_session) {
    const db = openSql();
    try {
      db.prepare("DELETE FROM sessions WHERE token = ?").run(existingCookies.olla_nest_session);
    } finally {
      db.close();
    }
  }

  const token = crypto.randomBytes(32).toString("hex");
  const expiresAt = new Date(Date.now() + 1000 * 60 * 60 * 12).toISOString().replace("T", " ").replace("Z", "");
  const db = openSql();
  try {
    db.prepare("INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)").run(token, user.id, expiresAt);
  } finally {
    db.close();
  }

  const isSecure = (req?.headers["x-forwarded-proto"] === "https") || req?.secure;
  const secureFlag = isSecure ? "; Secure" : "";
  res.setHeader("Set-Cookie", `olla_nest_session=${encodeURIComponent(token)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200${secureFlag}`);
}

function logout(req) {
  const token = parseCookies(req).olla_nest_session;
  if (!token) return;
  const db = openSql();
  try {
    db.prepare("DELETE FROM sessions WHERE token = ?").run(token);
  } finally {
    db.close();
  }
}

function forceLogoutUser(userId) {
  const db = openSql();
  try {
    db.prepare("DELETE FROM sessions WHERE user_id = ?").run(userId);
  } finally {
    db.close();
  }
}

// Startup cleanup: remove expired sessions
function cleanExpiredSessions() {
  const db = openSql();
  try {
    db.prepare("DELETE FROM sessions WHERE expires_at < datetime('now')").run();
  } catch (_) {
    // non-fatal
  } finally {
    db.close();
  }
}

// Run cleanup now and every hour
cleanExpiredSessions();
setInterval(cleanExpiredSessions, 60 * 60 * 1000);

// Provide a sessions-like iterable for legacy admin endpoints (active sessions listing)
// This is a compatibility shim — backed by DB at query time
const sessions = {
  [Symbol.iterator]() {
    const db = openSql();
    let rows;
    try {
      rows = db.prepare("SELECT token, user_id, expires_at FROM sessions WHERE expires_at > datetime('now')").all();
    } finally {
      db.close();
    }
    let i = 0;
    return {
      next() {
        if (i >= rows.length) return { done: true };
        const r = rows[i++];
        return { done: false, value: [r.token, { user: { id: r.user_id }, expiresAt: new Date(r.expires_at).getTime() }] };
      }
    };
  },
  delete(token) {
    const db = openSql();
    try { db.prepare("DELETE FROM sessions WHERE token = ?").run(token); } finally { db.close(); }
  },
};

module.exports = { sessions, parseCookies, sessionUser, setSession, logout, forceLogoutUser, requireAuth, requireAdmin, hasRight };
