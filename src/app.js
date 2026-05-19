/**
 * @file src/app.js
 * @description Express app setup, middleware, and router mounting.
 * Exports { app, server }.
 */

const express = require("express");
const http = require("http");
const path = require("path");
const fs = require("fs");
let pty;
try { pty = require("node-pty"); } catch { pty = null; }
const { WebSocketServer } = require("ws");

const { STATIC_DIR, DATA_DIR, PORT } = require("./config");

// Database
const { openSql, rows, one } = require("./db/index");

// DB helpers
const { setting, setSetting, settingsState } = require("./db/settings");
const { migrateDocumentsJson } = require("./db/schema");

// Middleware
const { enforceDockerRuntime, securityHeaders, checkChatRateLimit, loginAttempts, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS } = require("./middleware/security");
const { sessions, parseCookies, sessionUser, setSession, requireAuth, requireAdmin, hasRight } = require("./middleware/auth");

// Services
const monitor = require("./services/monitor");
const { encryptKey, decryptKey } = require("./services/crypto");
const { ollamaUrl, cleanBaseUrl, syncOllamaModels } = require("./services/ollama");
const { callProvider, callProviderStream, resolveProvider, mirrorApiModelToModels } = require("./services/providers");
const { routeModel } = require("./services/router");
const { workspaceForUser, normalizePermissionMode, writeLocalArtifacts, extractArtifacts, cleanModelOutput } = require("./services/workspace");
const { buildSystemPrompt, buildContextMessages, getActiveChat, getActiveChatSession, archiveCurrentChat, buildChatObject, parseMessage, appendAudit, appendTrace } = require("./services/chat");

// Models
const { parseModel, uid, safeJson } = require("./models/model");
const { publicUser, getUsers, USER_SELECT, allowedModelIds, allowedModels, effectiveAccess, userOverrides, userGroupIds, roleCatalog, permissionCatalog } = require("./models/user");

// Config
const { DEFAULT_ADMIN_EMAIL, DEFAULT_ADMIN_PASSWORD, DEFAULT_USER_PASSWORD } = require("./config");

// Enforce Docker runtime before doing anything else
enforceDockerRuntime();

const app = express();

app.use(express.json({ limit: "512kb" }));

// Request counting metrics
app.use((req, res, next) => {
  monitor.inc("requests.total");
  res.on("finish", () => { if (res.statusCode >= 500) monitor.inc("requests.errors"); });
  next();
});

// Security headers
app.use(securityHeaders);

// Static assets
app.use(express.static(STATIC_DIR, {
  etag: true,
  lastModified: true,
  setHeaders(res, filePath) {
    // JS/CSS: short cache (5 min) so Docker rebuilds are picked up quickly
    // Fonts/images: aggressive cache (1 day) — they never change between releases
    // HTML: never cache — pages boot the app with fresh session checks
    if (/\.(woff2?|ttf|otf|png|jpg|svg|ico)$/.test(filePath)) {
      res.setHeader("Cache-Control", "public, max-age=86400, stale-while-revalidate=3600");
    } else if (/\.(js|css)$/.test(filePath)) {
      res.setHeader("Cache-Control", "public, max-age=300, stale-while-revalidate=60");
    } else {
      res.setHeader("Cache-Control", "no-cache");
    }
  },
}));

// Shared deps object passed to all route factories
const deps = {
  // DB
  openSql, rows, one,
  // Settings
  setting, setSetting, settingsState,
  // Auth middleware
  sessions, parseCookies, sessionUser, setSession, requireAuth, requireAdmin, hasRight,
  // Security
  checkChatRateLimit, loginAttempts, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS,
  // Crypto
  encryptKey, decryptKey,
  // Ollama
  ollamaUrl, cleanBaseUrl, syncOllamaModels,
  // Providers
  callProvider, callProviderStream, resolveProvider, mirrorApiModelToModels,
  // Router
  routeModel,
  // Workspace
  workspaceForUser, normalizePermissionMode, writeLocalArtifacts, extractArtifacts, cleanModelOutput,
  // Chat
  buildSystemPrompt, buildContextMessages, getActiveChat, getActiveChatSession, archiveCurrentChat,
  buildChatObject, parseMessage, appendAudit, appendTrace,
  // Models
  parseModel, uid, safeJson,
  publicUser, getUsers, USER_SELECT, allowedModelIds, allowedModels,
  effectiveAccess, userOverrides, userGroupIds, roleCatalog, permissionCatalog,
  // Config
  DEFAULT_ADMIN_EMAIL, DEFAULT_ADMIN_PASSWORD, DEFAULT_USER_PASSWORD,
};

// ── Mount API routes ──────────────────────────────────────────────────────────

app.use("/api/auth", require("./routes/auth")(deps));
app.use("/api/bootstrap", require("./routes/bootstrap")(deps));
app.use("/api/account", require("./routes/account")(deps));

// State routes: /api/state, /api/ollama/ping, /api/ollama/models
app.use("/api", require("./routes/state")(deps));

// Chat routes: /api/chat (POST, DELETE), /api/chat/stream, /api/chat/clear, /api/chat/feedback
app.use("/api/chat", require("./routes/chat")(deps));

// /api/feedback — registered in chat router at /api/chat/feedback; also expose at /api/feedback
app.post("/api/feedback", requireAuth, (req, res) => {
  // Redirect to the chat router's feedback handler by forwarding
  // We re-implement inline to avoid circular dependency
  const { openSql } = deps;
  const { uid } = deps;
  const db = openSql();
  try {
    const { messageId, sessionId, rating, comment } = req.body;
    if (!messageId || !sessionId || (rating !== 1 && rating !== -1)) {
      return res.status(400).json({ error: "messageId, sessionId, and rating (1 or -1) are required" });
    }
    db.prepare("INSERT INTO feedback (id, message_id, session_id, user_id, rating, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
      uid("fb"), messageId, sessionId, req.user.id, rating, comment || null, new Date().toISOString()
    );
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.use("/api/threads", require("./routes/threads")(deps));
app.use("/api/workspace", require("./routes/workspace")(deps));

// Admin routes
// users.js handles both /api/admin/users/* AND /api/admin/sessions/*
app.use("/api/admin", require("./routes/admin/users")(deps));
// models.js handles /api/admin/models/:id/governance AND /api/admin/ollama/ping
app.use("/api/admin", require("./routes/admin/models")(deps));
app.use("/api/admin/providers", require("./routes/admin/providers")(deps));
// settings.js handles /api/admin/settings, /api/admin/departments, /api/admin/backup
app.use("/api/admin", require("./routes/admin/settings")(deps));
// reports.js handles /api/admin/reports AND /api/admin/feedback
app.use("/api/admin", require("./routes/admin/reports")(deps));
app.use("/api/admin/teams", require("./routes/admin/teams")(deps));
app.use("/api/admin/overrides", require("./routes/admin/overrides")(deps));
app.use("/api/admin/health", require("./routes/admin/health")(deps));

// Page routes (including 404 catch-all) must be last
app.use(require("./routes/pages")(deps));

// ── Global error handler ──────────────────────────────────────────────────────
// Catches any unhandled synchronous error thrown from any route handler.
// Without this, Express returns a raw HTML "Internal Server Error" page —
// which causes JSON.parse crashes on the frontend and hides the real error.
// This single handler covers all 30+ routes that have try/finally but no catch.
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error(`[Express] ${req.method} ${req.path} — ${err.message}`, err.stack);
  if (res.headersSent) return;
  // API routes → JSON error; page routes → redirect to login
  if (req.path.startsWith("/api/")) {
    res.status(500).json({ error: err.message || "Internal server error" });
  } else {
    res.status(500).send("Internal Server Error");
  }
});

// ── HTTP server + WebSocket terminal ──────────────────────────────────────────
const server = http.createServer(app);

// ─── WebSocket Terminal (PTY) ─────────────────────────────────────────────────
// The terminal is exposed at ws://<host>/ws/terminal.
// Auth: the session cookie is read during the HTTP upgrade handshake (before the
//   WebSocket is established) — only users with workspace:build or admin role are allowed.
// If node-pty is not installed (e.g. in environments that cannot compile native modules),
//   the WebSocket connection is accepted then immediately closed with an error message.
// The PTY is spawned in the user's workspace_root directory so shell commands run there.
// Bidirectional bridge:
//   PTY output → JSON { type:"output", data:"…" } sent to the WebSocket client (xterm.js)
//   WebSocket messages → type:"input" (keystrokes) or type:"resize" (terminal size change)
const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  if (req.url !== "/ws/terminal") { socket.destroy(); return; }

  // Auth: parse session cookie
  const cookies = Object.fromEntries(
    String(req.headers.cookie || "").split(";")
      .map(s => s.trim()).filter(Boolean)
      .map(s => { const i = s.indexOf("="); return [s.slice(0, i), decodeURIComponent(s.slice(i + 1))]; })
  );
  const token = cookies.olla_nest_session;
  let user = null;
  if (token) {
    const wsDb = openSql();
    try {
      const row = wsDb.prepare(
        `SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ? AND s.expires_at > datetime('now')`
      ).get(token);
      if (row) user = publicUser(row);
    } finally { wsDb.close(); }
  }
  if (!user) { socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n"); socket.destroy(); return; }

  const hasTerminalAccess = user.role === "admin" || (user.rights || []).includes("workspace:build");
  if (!hasTerminalAccess) { socket.write("HTTP/1.1 403 Forbidden\r\n\r\n"); socket.destroy(); return; }

  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit("connection", ws, req, user);
  });
});

wss.on("connection", (ws, req, user) => {
  if (!pty) {
    ws.send(JSON.stringify({ type: "error", message: "node-pty not available in this environment." }));
    ws.close();
    return;
  }

  // Determine workspace root for this user
  let cwd;
  try {
    const db = openSql();
    try {
      const prefs = db.prepare("SELECT workspace_root FROM workspace_prefs WHERE user_id = ?").get(user.id);
      cwd = prefs?.workspace_root || path.join(DATA_DIR, "workspace");
    } finally { db.close(); }
  } catch { cwd = path.join(DATA_DIR, "workspace"); }

  // Ensure workspace dir exists
  try { fs.mkdirSync(cwd, { recursive: true }); } catch {}

  const shell = process.env.SHELL || "/bin/bash";
  const ptyEnv = { ...process.env };
  delete ptyEnv.SECRET_KEY;
  delete ptyEnv.SESSION_SECRET;
  delete ptyEnv.DEFAULT_ADMIN_PASSWORD;
  delete ptyEnv.DEFAULT_USER_PASSWORD;
  const term = pty.spawn(shell, [], {
    name: "xterm-256color",
    cols: 120,
    rows: 30,
    cwd,
    env: {
      ...ptyEnv,
      TERM: "xterm-256color",
      HOME: process.env.HOME || "/root",
      USER: user.name || "user",
      OLLA_USER: user.name,
      OLLA_ROLE: user.role,
    },
  });

  appendAudit(user.name, "terminal.session.open", `Terminal session started in ${cwd}`);

  // PTY → WebSocket
  term.onData((data) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ type: "output", data }));
  });

  term.onExit(({ exitCode }) => {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify({ type: "exit", code: exitCode }));
      ws.close();
    }
  });

  // WebSocket → PTY
  ws.on("message", (raw) => {
    try {
      const msg = JSON.parse(raw);
      if (msg.type === "input") { term.write(msg.data); }
      else if (msg.type === "resize") { term.resize(Math.max(1, msg.cols), Math.max(1, msg.rows)); }
    } catch {}
  });

  ws.on("close", () => {
    try { term.kill(); } catch {}
    appendAudit(user.name, "terminal.session.close", "Terminal session ended");
  });

  ws.on("error", () => { try { term.kill(); } catch {} });
});

module.exports = { app, server, migrateDocumentsJson, syncOllamaModels, openSql };
