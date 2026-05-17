/**
 * @file server.js
 * @description Olla Nest — main Express server.
 *
 * Responsibilities:
 *   - Bootstraps SQLite database (openSql / seedSql) with all tables and seed data
 *   - Serves the static SPA assets (login, app, admin) with appropriate cache headers
 *   - Provides all REST API routes under /api/*
 *   - Implements the Auto Router: classifyRequest → routeModel → callProvider(Stream)
 *   - Manages session-based auth with HttpOnly cookies; CSRF guard via X-Requested-With
 *   - Encrypts API keys at rest using AES-256-GCM with a per-deployment SECRET_KEY
 *   - Exposes a WebSocket endpoint (/ws/terminal) that spawns a PTY shell for
 *     users with the workspace:build permission
 *   - On startup: migrates legacy documents.json data into SQLite, then syncs Ollama
 *
 * Key data flows:
 *   User sends message → POST /api/chat/stream (SSE)
 *     → syncOllamaModels (ensure DB reflects current Ollama state)
 *     → routeModel (score all allowed models; pick best)
 *     → resolveProvider (find the right provider config)
 *     → buildContextMessages (sliding window token-budget trimmer)
 *     → callProviderStream (stream tokens back via SSE)
 *     → persist user + assistant messages in chat_messages
 *     → optionally write code artifacts to the user's workspace folder
 */

const express = require("express");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const http = require("http");
const bcrypt = require("bcryptjs");
const { DatabaseSync } = require("node:sqlite");
const { WebSocketServer } = require("ws");
let pty;
try { pty = require("node-pty"); } catch { pty = null; }

const app = express();

// ── Constants ────────────────────────────────────────────────────────────────

/** TCP port the HTTP server listens on. Override with PORT env var. */
const PORT = process.env.PORT || 3000;

/**
 * Base URL of the Ollama instance visible from inside the container.
 * host.docker.internal resolves to the host's loopback address on Docker
 * Desktop (Mac/Windows).  On Linux you may need to set this to the container
 * gateway IP.  Override with OLLAMA_URL env var.
 */
const OLLAMA_URL = process.env.OLLAMA_URL || "http://host.docker.internal:11434";

/** Persistent data directory mounted inside the container (Docker volume). */
const DATA_DIR = path.join(__dirname, "data");

/**
 * Root folder where AI-generated code files are written when the user enables
 * "Save to workspace".  Can be overridden per-user via workspace_prefs table
 * or for the whole platform via the workspaceRoot setting.
 */
const DEFAULT_WORKSPACE_ROOT = process.env.WORKSPACE_ROOT || path.join(DATA_DIR, "workspace");

/** Path to the SQLite database file.  Override with SQLITE_PATH env var. */
const SQL_PATH = process.env.SQLITE_PATH || path.join(DATA_DIR, "olla-nest.sqlite");

/**
 * Legacy JSON document store path — only used for the one-time migration to
 * SQLite.  Once migrated it is renamed to documents.json.migrated.
 */
const DOC_PATH = process.env.DOCUMENT_DB_PATH || path.join(DATA_DIR, "documents.json");

/**
 * Default admin credentials seeded on first boot.  The /api/bootstrap endpoint
 * returns these only while the default password is still in use so the UI can
 * display an auto-fill prompt.  Change the password immediately after first login.
 */
const DEFAULT_ADMIN_EMAIL = process.env.DEFAULT_ADMIN_EMAIL || "admin@ollanest.local";
const DEFAULT_ADMIN_PASSWORD = process.env.DEFAULT_ADMIN_PASSWORD || "CHANGE_ME_ON_FIRST_BOOT";
const DEFAULT_USER_PASSWORD = process.env.DEFAULT_USER_PASSWORD || "CHANGE_ME_ON_FIRST_BOOT";

/** Absolute path to the /public directory that contains all SPA HTML/JS/CSS. */
const STATIC_DIR = path.join(__dirname, "public");

/**
 * In-memory session store.  Maps random 32-byte hex token → { user, expiresAt }.
 * Sessions survive server restarts only if the container is not restarted
 * (i.e. they are ephemeral).  12-hour TTL set in setSession().
 */
const sessions = new Map();

// In-memory login rate limiter: track failed attempts per IP
const loginAttempts = new Map(); // ip -> { count, resetAt }

/** Maximum failed login attempts before a 15-minute lockout is triggered. */
const LOGIN_MAX_ATTEMPTS = 10;
const LOGIN_WINDOW_MS = 15 * 60 * 1000; // 15 minutes

// Per-user chat rate limiter (sliding window — timestamps per user)
const chatRateLimiter = new Map(); // userId -> [timestamp, ...]

/**
 * Checks whether a user is within their per-minute chat rate limit.
 *
 * Uses a sliding-window algorithm: keep only timestamps that fall within the
 * last 60 seconds, then compare the count against the user's configured limit.
 *
 * @param {string} userId - The authenticated user's ID.
 * @param {number} limitPerMinute - The user's api_rate_limit_per_minute setting.
 *   A value of 0 or falsy means unlimited.
 * @returns {boolean} true if the request is allowed, false if the limit is exceeded.
 */
function checkChatRateLimit(userId, limitPerMinute) {
  if (!limitPerMinute || limitPerMinute <= 0) return true; // unlimited
  const now = Date.now();
  const windowStart = now - 60_000;
  // 1. Discard timestamps older than the 60-second window
  const times = (chatRateLimiter.get(userId) || []).filter(t => t > windowStart);
  // 2. Reject if already at or over the limit
  if (times.length >= limitPerMinute) return false;
  // 3. Record this request and persist the updated window
  times.push(now);
  chatRateLimiter.set(userId, times);
  return true;
}

// Clean up old rate limiter entries every 5 minutes
setInterval(() => {
  const cutoff = Date.now() - 60_000;
  for (const [uid, times] of chatRateLimiter) {
    const fresh = times.filter(t => t > cutoff);
    if (fresh.length === 0) chatRateLimiter.delete(uid);
    else chatRateLimiter.set(uid, fresh);
  }
}, 5 * 60 * 1000).unref();

/**
 * Storage mode determines which SQL/document/realtime providers are configured
 * in the settings table on first boot.  "local" uses SQLite + JSON; "production"
 * seeds PostgreSQL/MongoDB/Redis placeholders (not yet fully implemented).
 */
const STORAGE_MODE = process.env.STORAGE_MODE || "local";

/**
 * 256-bit key used for AES-256-GCM encryption of API keys stored in
 * api_providers.api_key_enc.  MUST be set to a stable value in production
 * via the SECRET_KEY env var — if left as a random value, keys become
 * unreadable after every container restart.
 */
const SECRET_KEY = process.env.SECRET_KEY || crypto.randomBytes(32).toString("hex");

/** Legacy session secret (currently unused — cookie-based sessions are used instead). */
const SESSION_SECRET = process.env.SESSION_SECRET || "change-this-in-production";

/**
 * Guards against running Olla Nest outside Docker.
 *
 * Olla Nest depends on Docker networking (host.docker.internal) and assumes
 * the /data volume is mounted.  Running it bare on the host will silently
 * break Ollama connectivity and may write data to unexpected paths.
 *
 * The check is bypassed by setting ALLOW_NON_DOCKER=1 for diagnostic runs only.
 */
function enforceDockerRuntime() {
  const inDocker = fs.existsSync("/.dockerenv") || process.env.OLLA_NEST_DOCKER_RUNTIME === "true";
  if (!inDocker && process.env.ALLOW_NON_DOCKER !== "1") {
    console.error("Olla Nest is Docker-only. Start it with: docker compose up --build");
    console.error("For one-off diagnostics only, set ALLOW_NON_DOCKER=1.");
    process.exit(1);
  }
}

enforceDockerRuntime();

app.use(express.json({ limit: "512kb" }));

// ─── Security headers ─────────────────────────────────────────────────────────
app.use((req, res, next) => {
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-XSS-Protection", "1; mode=block");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.setHeader("Content-Security-Policy",
    "default-src 'self'; script-src 'self' 'unsafe-inline' cdn.jsdelivr.net cdnjs.cloudflare.com; style-src 'self' 'unsafe-inline' fonts.googleapis.com cdnjs.cloudflare.com; font-src 'self' fonts.gstatic.com; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'none';"
  );
  // HSTS — only send over HTTPS connections
  if (req.headers["x-forwarded-proto"] === "https" || req.secure) {
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
  next();
});

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

// ── Utility helpers ───────────────────────────────────────────────────────────

/**
 * Generates a collision-resistant, human-readable ID.
 *
 * Format: `{prefix}-{base36 timestamp}-{6 random chars}`
 * Example: "msg-lrk4z2-a8xf3q"
 *
 * This is not a UUID — it is intentionally shorter and sortable by
 * insertion time (the timestamp component is the dominant part).
 *
 * @param {string} prefix - Short string to prepend (e.g. "msg", "chat", "u").
 * @returns {string} A unique ID string.
 */
function uid(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Creates the /data directory if it does not already exist.
 * Called before every database open and document read to ensure the
 * Docker volume mount has been initialised.
 */
function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
}

/**
 * Opens (or creates) the SQLite database, creates all tables idempotently,
 * adds performance indexes, and runs seedSql() to populate defaults on
 * first boot.  Also applies any pending ALTER TABLE column migrations so
 * the schema always matches the current codebase without a manual migration step.
 *
 * Called at the start of every request handler — DatabaseSync is synchronous
 * and lightweight enough that opening/closing per-request is safe and avoids
 * connection-pooling complexity.
 *
 * @returns {DatabaseSync} An open SQLite database handle.  Caller MUST call
 *   db.close() in a finally block to release the file lock.
 */
function openSql() {
  ensureDataDir();
  const db = new DatabaseSync(SQL_PATH);
  db.exec(`
    -- settings: key/value store for all platform configuration (router flags, API keys, workspace root, etc.)
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
    -- departments: org units (General, Product, Support) — drive default rights via deptDefaultRights setting
    CREATE TABLE IF NOT EXISTS departments (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL
    );
    -- groups: logical collections used in access_grants (All Employees, Builders, Admins)
    CREATE TABLE IF NOT EXISTS groups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL
    );
    -- teams: optional team labels that can be attached to users (separate from departments)
    CREATE TABLE IF NOT EXISTS teams (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL UNIQUE,
      description TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    );
    -- users: all platform accounts; rights is a JSON array of permission keys; password_hash is bcrypt
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      email TEXT UNIQUE,
      password_hash TEXT,
      role TEXT NOT NULL,
      rights TEXT NOT NULL DEFAULT '["chat:use"]',
      department_id TEXT,
      active INTEGER NOT NULL DEFAULT 1
    );
    -- user_groups: many-to-many join between users and groups (used for access_grants resolution)
    CREATE TABLE IF NOT EXISTS user_groups (
      user_id TEXT NOT NULL,
      group_id TEXT NOT NULL,
      PRIMARY KEY (user_id, group_id)
    );
    -- models: all known AI models (Ollama-local + approved external); id = "ollama:name" or "provId:modelId"
    --   capabilities is a JSON array (e.g. ["coding","general"]); privacy is "local" or "external"
    --   status: available | missing | disabled | configured
    CREATE TABLE IF NOT EXISTS models (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      provider TEXT NOT NULL,
      model_ref TEXT NOT NULL,
      status TEXT NOT NULL,
      capabilities TEXT NOT NULL,
      speed_score INTEGER NOT NULL,
      quality_score INTEGER NOT NULL,
      privacy TEXT NOT NULL,
      context_size INTEGER,
      last_seen_at TEXT
    );
    -- access_grants: links a subject (user/group/department) to a model with can_use flag
    CREATE TABLE IF NOT EXISTS access_grants (
      id TEXT PRIMARY KEY,
      subject_type TEXT NOT NULL,
      subject_id TEXT NOT NULL,
      model_id TEXT NOT NULL,
      can_use INTEGER NOT NULL DEFAULT 1
    );
    -- role_catalog: named sets of permissions (e.g. "ai-developer"); permissions is JSON array of keys
    CREATE TABLE IF NOT EXISTS role_catalog (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      description TEXT,
      permissions TEXT NOT NULL DEFAULT '[]',
      system_role INTEGER NOT NULL DEFAULT 0
    );
    -- permission_catalog: master list of all valid permission keys with human labels and risk levels
    CREATE TABLE IF NOT EXISTS permission_catalog (
      key TEXT PRIMARY KEY,
      category TEXT NOT NULL,
      description TEXT NOT NULL,
      risk_level TEXT NOT NULL DEFAULT 'low'
    );
    -- user_overrides: per-user permission exceptions (allow/deny a specific key, optionally time-limited)
    CREATE TABLE IF NOT EXISTS user_overrides (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      permission_key TEXT NOT NULL,
      model_id TEXT,
      effect TEXT NOT NULL,
      reason TEXT,
      expires_at TEXT,
      created_at TEXT NOT NULL
    );
    -- chat_sessions: one row per conversation thread; is_active=1 means the current open thread
    CREATE TABLE IF NOT EXISTS chat_sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      title TEXT NOT NULL DEFAULT 'New Chat',
      pinned INTEGER NOT NULL DEFAULT 0,
      archived INTEGER NOT NULL DEFAULT 0,
      unread INTEGER NOT NULL DEFAULT 0,
      is_active INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );
    -- chat_messages: every user/assistant turn; live=0 means the model call failed; artifacts_json holds
    --   saved workspace files; extracted_files_json holds code blocks available for client-side save
    CREATE TABLE IF NOT EXISTS chat_messages (
      id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      mode TEXT,
      model_id TEXT,
      model_name TEXT,
      route_reason TEXT,
      live INTEGER DEFAULT 1,
      artifacts_json TEXT,
      extracted_files_json TEXT,
      created_at TEXT NOT NULL
    );
    -- audit_events: immutable log of significant actions (login, chat, admin changes) for compliance
    CREATE TABLE IF NOT EXISTS audit_events (
      id TEXT PRIMARY KEY,
      actor TEXT,
      action TEXT NOT NULL,
      detail TEXT,
      extra_json TEXT,
      created_at TEXT NOT NULL
    );
    -- router_traces: debug log of every Auto Router decision — which model was picked and why
    CREATE TABLE IF NOT EXISTS router_traces (
      id TEXT PRIMARY KEY,
      user_id TEXT,
      session_id TEXT,
      message TEXT,
      mode TEXT,
      selected_model_id TEXT,
      tags_json TEXT,
      candidates_json TEXT,
      live INTEGER DEFAULT 1,
      created_at TEXT NOT NULL
    );
    -- workspace_prefs: per-user override for workspace root path and write-permission mode
    CREATE TABLE IF NOT EXISTS workspace_prefs (
      user_id TEXT PRIMARY KEY,
      workspace_root TEXT NOT NULL,
      permission_mode TEXT NOT NULL DEFAULT 'default',
      updated_at TEXT NOT NULL
    );
    -- api_providers: external AI provider credentials (Anthropic, OpenAI, Groq, custom); api_key_enc
    --   stores the AES-256-GCM encrypted key — never stored in plaintext
    CREATE TABLE IF NOT EXISTS api_providers (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL UNIQUE,
      type TEXT NOT NULL,
      base_url TEXT,
      api_key_enc TEXT NOT NULL,
      enabled INTEGER DEFAULT 1,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );
    -- api_models: models fetched from an external provider's /models endpoint; is_approved=1 mirrors
    --   the row into the main models table so the Auto Router can see and score it
    CREATE TABLE IF NOT EXISTS api_models (
      id TEXT PRIMARY KEY,
      provider_id TEXT NOT NULL,
      model_id TEXT NOT NULL,
      display_name TEXT NOT NULL,
      capability_tags TEXT,
      context_window INTEGER,
      is_approved INTEGER DEFAULT 0,
      governance_tag TEXT DEFAULT 'approved',
      created_at TEXT NOT NULL
    );
    -- feedback: thumbs-up/down ratings on individual assistant messages; rating is 1 or -1
    CREATE TABLE IF NOT EXISTS feedback (
      id TEXT PRIMARY KEY,
      message_id TEXT NOT NULL,
      session_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      rating INTEGER NOT NULL,
      comment TEXT,
      created_at TEXT NOT NULL
    );
  `);
  db.exec("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL;");
  // Performance indexes — safe to add idempotently on every open
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
    CREATE INDEX IF NOT EXISTS idx_chat_messages_created_at ON chat_messages(created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active ON chat_sessions(user_id, is_active);
    CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at);
    CREATE INDEX IF NOT EXISTS idx_router_traces_created_at ON router_traces(created_at);
    CREATE INDEX IF NOT EXISTS idx_feedback_message_id ON feedback(message_id);
  `);
  seedSql(db);
  return db;
}

/**
 * Reads (or initialises) the legacy documents.json flat-file store.
 *
 * This file was the original persistence layer before SQLite was introduced.
 * It is kept alive only to support the one-time migrateDocumentsJson() call on
 * startup.  After migration it is renamed to documents.json.migrated and this
 * function effectively becomes a no-op.
 *
 * @returns {{ chats: object, audit: Array, routerTraces: Array, workspacePrefs: object }} Parsed doc store.
 */
function readDocs() {
  ensureDataDir();
  if (!fs.existsSync(DOC_PATH)) {
    fs.writeFileSync(
      DOC_PATH,
      JSON.stringify(
        {
          provider: "json-document-store",
          chats: {},
          audit: [],
          routerTraces: [],
          workspacePrefs: {},
        },
        null,
        2
      )
    );
  }
  const docs = JSON.parse(fs.readFileSync(DOC_PATH, "utf8"));
  let changed = false;
  for (const [key, fallback] of Object.entries({ chats: {}, audit: [], routerTraces: [], workspacePrefs: {}, chatHistory: {} })) {
    if (!docs[key]) {
      docs[key] = fallback;
      changed = true;
    }
  }
  if (changed) writeDocs(docs);
  return docs;
}

/**
 * Persists the in-memory documents object back to documents.json.
 * @param {object} docs - The full document store object returned by readDocs().
 */
function writeDocs(docs) {
  fs.writeFileSync(DOC_PATH, JSON.stringify(docs, null, 2));
}

/**
 * Reads a single platform setting from the settings table.
 * Returns `fallback` if the key does not exist.
 * Automatically coerces the stored string "true"/"false" to booleans.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @param {string} key - Settings key (e.g. "routerEnabled").
 * @param {*} fallback - Value returned when the key is absent.
 * @returns {string|boolean} The stored value or fallback.
 */
function setting(db, key, fallback) {
  const row = db.prepare("SELECT value FROM settings WHERE key = ?").get(key);
  if (!row) return fallback;
  if (row.value === "true") return true;
  if (row.value === "false") return false;
  return row.value;
}

/**
 * Upserts a setting into the settings table (INSERT OR REPLACE).
 * @param {DatabaseSync} db - Open database handle.
 * @param {string} key - Settings key.
 * @param {*} value - Value to store (converted to string).
 */
function setSetting(db, key, value) {
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").run(key, String(value));
}

/**
 * Returns the total row count for a given table.  Used by seedSql() to check
 * whether default data already exists before attempting to insert it.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @param {string} table - Table name (not user-supplied — always a literal in this codebase).
 * @returns {number} Row count.
 */
function tableCount(db, table) {
  return db.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get().count;
}

/**
 * Adds a column to a table only if it does not already exist.
 * Used for schema migrations — safe to call repeatedly on every startup.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @param {string} table - Target table name.
 * @param {string} column - Column name to add.
 * @param {string} definition - SQL type + constraints (e.g. "TEXT NOT NULL DEFAULT ''").
 */
function ensureColumn(db, table, column, definition) {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all().map((row) => row.name);
  if (!columns.includes(column)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
}

/**
 * Seeds all default data into a fresh database on first boot.
 * Also runs additive column migrations (ensureColumn) on every boot so the
 * schema stays up to date as new fields are added.
 *
 * Seed blocks are guarded by tableCount checks — they run exactly once and
 * are skipped on subsequent restarts so existing data is never overwritten.
 *
 * Data seeded:
 *   - Extra columns on users and models tables (migration)
 *   - Default settings (routerEnabled, OLLAMA_URL, etc.)
 *   - Three departments, three groups, 18 permissions, 9 roles
 *   - Four demo users (admin, employee, builder, support)
 *   - Default group memberships
 *
 * @param {DatabaseSync} db - Open database handle.
 */
function seedSql(db) {
  [
    ["employee_id", "TEXT"],
    ["designation", "TEXT"],
    ["team", "TEXT"],
    ["branch", "TEXT"],
    ["manager", "TEXT"],
    ["organization", "TEXT NOT NULL DEFAULT 'Olla Nest'"],
    ["ai_access_tier", "TEXT NOT NULL DEFAULT 'standard'"],
    ["daily_token_limit", "INTEGER NOT NULL DEFAULT 50000"],
    ["monthly_token_limit", "INTEGER NOT NULL DEFAULT 1000000"],
    ["gpu_quota_minutes", "INTEGER NOT NULL DEFAULT 120"],
    ["vram_limit_mb", "INTEGER NOT NULL DEFAULT 8192"],
    ["concurrent_model_limit", "INTEGER NOT NULL DEFAULT 1"],
    ["api_rate_limit_per_minute", "INTEGER NOT NULL DEFAULT 30"],
    ["max_context_size", "INTEGER NOT NULL DEFAULT 8192"],
    ["mfa_enabled", "INTEGER NOT NULL DEFAULT 0"],
    ["security_risk_score", "INTEGER NOT NULL DEFAULT 10"],
    ["access_status", "TEXT NOT NULL DEFAULT 'active'"],
    ["access_expires_at", "TEXT"],
    ["last_active_at", "TEXT"],
  ].forEach(([column, definition]) => ensureColumn(db, "users", column, definition));

  [
    ["governance_tier", "TEXT NOT NULL DEFAULT 'approved-local'"],
    ["resource_tier", "TEXT NOT NULL DEFAULT 'standard'"],
    ["gpu_required", "INTEGER NOT NULL DEFAULT 0"],
    ["max_concurrency", "INTEGER NOT NULL DEFAULT 2"],
    ["max_context_size", "INTEGER"],
    ["external_cost_tier", "TEXT NOT NULL DEFAULT 'local-free'"],
    ["sensitive_allowed", "INTEGER NOT NULL DEFAULT 1"],
  ].forEach(([column, definition]) => ensureColumn(db, "models", column, definition));

  if (tableCount(db, "settings") === 0) {
    [
      ["activeUserId", "u-admin"],
      ["routerEnabled", "true"],
      ["allowApiModels", "false"],
      ["localOnlyDefault", "true"],
      ["localWritesEnabled", "true"],
      ["workspaceRoot", DEFAULT_WORKSPACE_ROOT],
      ["localPermissionMode", "default"],
      ["ollamaUrl", OLLAMA_URL],
      ["apiModelProvider", "not-configured"],
      ["sqlProvider", STORAGE_MODE === "production" ? "postgresql" : "sqlite"],
      ["documentProvider", STORAGE_MODE === "production" ? "mongodb" : "json-document-store"],
      ["realtimeProvider", STORAGE_MODE === "production" ? "redis" : "in-memory"],
    ].forEach(([key, value]) => setSetting(db, key, value));
  }

  if (tableCount(db, "departments") === 0) {
    [
      ["dept-general", "General"],
      ["dept-product", "Product"],
      ["dept-support", "Support"],
    ].forEach((row) => db.prepare("INSERT INTO departments (id, name) VALUES (?, ?)").run(...row));
  }

  if (tableCount(db, "groups") === 0) {
    [
      ["group-all", "All Employees"],
      ["group-builders", "Builders"],
      ["group-admins", "Admins"],
    ].forEach((row) => db.prepare("INSERT INTO groups (id, name) VALUES (?, ?)").run(...row));
  }

  if (tableCount(db, "permission_catalog") === 0) {
    [
      ["chat:use", "AI Usage", "Use the AI workspace", "low"],
      ["models:local:use", "Model Usage", "Use approved Ollama/local models", "low"],
      ["models:external:use", "Model Usage", "Use external premium AI providers", "high"],
      ["models:coding:use", "Model Usage", "Use coding models and coding workflows", "medium"],
      ["models:reasoning:use", "Model Usage", "Use reasoning models", "medium"],
      ["ollama:models:pull", "Ollama Governance", "Pull models into Ollama", "high"],
      ["ollama:models:import", "Ollama Governance", "Import custom/GGUF models", "high"],
      ["ollama:modelfile:create", "Ollama Governance", "Create models with Modelfiles", "high"],
      ["workspace:build", "Local Work", "Create local workspace files and access terminal shell", "critical"],
      ["files:upload", "AI Workflow", "Upload files to AI workflows", "medium"],
      ["tools:call", "AI Workflow", "Use tool calling", "high"],
      ["internet:use", "AI Workflow", "Use internet-enabled agents", "high"],
      ["agents:run", "AI Workflow", "Run AI agents", "high"],
      ["api:use", "Developer Access", "Use Olla Nest APIs", "medium"],
      ["audit:read", "Governance", "Read audit logs", "medium"],
      ["users:manage", "Administration", "Manage users", "high"],
      ["models:manage", "Administration", "Manage model governance", "high"],
      ["admin:manage", "Administration", "Manage platform settings", "critical"],
    ].forEach((row) => db.prepare("INSERT INTO permission_catalog (key, category, description, risk_level) VALUES (?, ?, ?, ?)").run(...row));
  }

  if (tableCount(db, "role_catalog") === 0) {
    [
      ["platform-owner", "Platform Owner", "Full control over the AI platform", ["admin:manage", "users:manage", "models:manage", "audit:read", "chat:use", "models:local:use", "models:external:use", "ollama:models:pull", "ollama:models:import", "ollama:modelfile:create", "api:use", "agents:run"], 1],
      ["ai-infra-admin", "AI Infrastructure Admin", "Manage Ollama infrastructure and model sources", ["models:manage", "models:local:use", "ollama:models:pull", "ollama:models:import", "ollama:modelfile:create", "audit:read"], 1],
      ["security-admin", "Security Admin", "Manage governance, audit, and risk", ["users:manage", "audit:read", "admin:manage"], 1],
      ["department-admin", "Department Admin", "Manage department users and access requests", ["users:manage", "audit:read", "chat:use"], 1],
      ["ai-developer", "AI Developer", "Build with coding models, tools, and local files", ["chat:use", "models:local:use", "models:coding:use", "workspace:build", "files:upload", "tools:call", "api:use"], 1],
      ["ai-analyst", "AI Analyst", "Use analysis and reasoning workflows", ["chat:use", "models:local:use", "models:reasoning:use", "files:upload"], 1],
      ["engineering-user", "Engineering User", "Use coding and local AI models", ["chat:use", "models:local:use", "models:coding:use", "workspace:build"], 1],
      ["research-user", "Research User", "Use reasoning models and knowledge workflows", ["chat:use", "models:local:use", "models:reasoning:use", "files:upload"], 1],
      ["viewer", "Viewer", "Read-only AI workspace visibility", ["chat:use"], 1],
    ].forEach(([id, name, description, permissions, systemRole]) => {
      db.prepare("INSERT INTO role_catalog (id, name, description, permissions, system_role) VALUES (?, ?, ?, ?, ?)").run(id, name, description, JSON.stringify(permissions), systemRole);
    });
  }

  if (tableCount(db, "users") === 0) {
    const adminHash = bcrypt.hashSync(DEFAULT_ADMIN_PASSWORD, 12);
    const userHash = bcrypt.hashSync(DEFAULT_USER_PASSWORD, 12);
    [
      ["u-admin", "Admin", DEFAULT_ADMIN_EMAIL, adminHash, "admin", JSON.stringify(["admin:manage", "chat:use", "models:manage", "users:manage"]), "dept-product"],
      ["u-user", "Employee", "employee@ollanest.local", userHash, "user", JSON.stringify(["chat:use"]), "dept-general"],
      ["u-builder", "Builder Employee", "builder@ollanest.local", userHash, "user", JSON.stringify(["chat:use", "workspace:build"]), "dept-product"],
      ["u-support", "Support Employee", "support@ollanest.local", userHash, "user", JSON.stringify(["chat:use", "workspace:review"]), "dept-support"],
    ].forEach((row) => db.prepare("INSERT INTO users (id, name, email, password_hash, role, rights, department_id) VALUES (?, ?, ?, ?, ?, ?, ?)").run(...row));
    [
      ["u-admin", "group-admins"],
      ["u-admin", "group-all"],
      ["u-user", "group-all"],
      ["u-builder", "group-all"],
      ["u-builder", "group-builders"],
      ["u-support", "group-all"],
    ].forEach((row) => db.prepare("INSERT INTO user_groups (user_id, group_id) VALUES (?, ?)").run(...row));
  }

  const userColumns = db.prepare("PRAGMA table_info(users)").all().map((row) => row.name);
  if (!userColumns.includes("email")) db.exec("ALTER TABLE users ADD COLUMN email TEXT");
  if (!userColumns.includes("password_hash")) db.exec("ALTER TABLE users ADD COLUMN password_hash TEXT");
  if (!userColumns.includes("rights")) db.exec("ALTER TABLE users ADD COLUMN rights TEXT NOT NULL DEFAULT '[\"chat:use\"]'");
  if (!userColumns.includes("auth_provider")) db.exec("ALTER TABLE users ADD COLUMN auth_provider TEXT NOT NULL DEFAULT 'local'");
  if (!userColumns.includes("phone")) db.exec("ALTER TABLE users ADD COLUMN phone TEXT NOT NULL DEFAULT ''");
  if (!userColumns.includes("avatar_initials")) db.exec("ALTER TABLE users ADD COLUMN avatar_initials TEXT NOT NULL DEFAULT ''");
  // chat_messages migrations
  const msgColumns = db.prepare("PRAGMA table_info(chat_messages)").all().map(r => r.name);
  if (!msgColumns.includes("tokens_used")) db.exec("ALTER TABLE chat_messages ADD COLUMN tokens_used INTEGER NOT NULL DEFAULT 0");
  if (!msgColumns.includes("latency_ms")) db.exec("ALTER TABLE chat_messages ADD COLUMN latency_ms INTEGER NOT NULL DEFAULT 0");
  const admin = db.prepare("SELECT id, email, password_hash FROM users WHERE id = 'u-admin'").get();
  if (admin && (!admin.email || !admin.password_hash)) {
    db.prepare("UPDATE users SET email = ?, password_hash = ? WHERE id = 'u-admin'").run(DEFAULT_ADMIN_EMAIL, bcrypt.hashSync(DEFAULT_ADMIN_PASSWORD, 12));
  }
}

/**
 * Heuristically infers capability tags from an Ollama model name.
 * Used when syncing Ollama models — Ollama itself does not expose capability metadata,
 * so we use regex patterns on the model name to determine what the model is good at.
 *
 * @param {string} modelName - The model's full name/tag (e.g. "deepseek-coder:6.7b").
 * @returns {string[]} Array of capability tags (e.g. ["coding", "debugging", "general"]).
 */
function inferCapabilities(modelName) {
  const text = modelName.toLowerCase();
  const caps = new Set(["general", "ask"]);
  if (/(qwen|coder|code|deepseek|starcoder|devstral)/.test(text)) ["coding", "debugging", "build", "review", "project"].forEach((c) => caps.add(c));
  if (/(think|reason|r1|qwq|gemma|llama|mistral|granite)/.test(text)) ["reasoning", "analysis", "learn"].forEach((c) => caps.add(c));
  if (/(gemma|llama|mistral|granite|phi)/.test(text)) ["writing", "summary"].forEach((c) => caps.add(c));
  if (/(ocr|vision|vl|llava|minicpm-v|bakllava)/.test(text)) ["ocr", "vision", "document"].forEach((c) => caps.add(c));
  if (/(med|clinical|health)/.test(text)) ["medical", "summary", "analysis"].forEach((c) => caps.add(c));
  return Array.from(caps);
}

/**
 * Estimates speed and quality scores for an Ollama model based on its name and
 * file size.  Smaller models are faster; larger models with known-quality
 * architectures get a quality bonus.
 *
 * Scores are stored in the models table and used by the Auto Router's weighted
 * scoring formula: score = capability + speed*w + quality*w + privacy*w.
 *
 * @param {string} modelName - Model name string.
 * @param {number} [sizeBytes=0] - Model file size in bytes from Ollama /api/tags.
 * @returns {{ speedScore: number, qualityScore: number }} Both values clamped to [10, 100].
 */
function inferScores(modelName, sizeBytes = 0) {
  const text = modelName.toLowerCase();
  const sizeGb = Number(sizeBytes || 0) / 1024 ** 3;
  const speedScore = sizeGb && sizeGb < 2 ? 95 : sizeGb < 5 ? 78 : sizeGb < 10 ? 58 : sizeGb < 20 ? 38 : 25;
  let qualityScore = sizeGb ? Math.min(95, 45 + Math.round(sizeGb * 3)) : 60;
  if (/(think|reason|qwen|gemma|llama|mistral|granite)/.test(text)) qualityScore += 8;
  if (/(ocr|med|coder|code)/.test(text)) qualityScore += 6;
  return {
    speedScore: Math.max(10, Math.min(100, speedScore)),
    qualityScore: Math.max(10, Math.min(100, qualityScore)),
  };
}

/**
 * Inserts or updates a model row in the models table (INSERT OR UPDATE).
 * Called for every model returned by Ollama /api/tags during syncOllamaModels().
 *
 * @param {DatabaseSync} db - Open database handle.
 * @param {{ id, name, provider, modelRef, status, capabilities, speedScore,
 *           qualityScore, privacy, contextSize, lastSeenAt }} model - Model descriptor.
 */
function upsertModel(db, model) {
  db.prepare(
    `INSERT INTO models
      (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      + ` ON CONFLICT(id) DO UPDATE SET
        name = excluded.name,
        provider = excluded.provider,
        model_ref = excluded.model_ref,
        status = excluded.status,
        capabilities = excluded.capabilities,
        speed_score = excluded.speed_score,
        quality_score = excluded.quality_score,
        privacy = excluded.privacy,
        context_size = excluded.context_size,
        last_seen_at = excluded.last_seen_at`
  ).run(
    model.id,
    model.name,
    model.provider,
    model.modelRef,
    model.status,
    JSON.stringify(model.capabilities),
    model.speedScore,
    model.qualityScore,
    model.privacy,
    model.contextSize || null,
    model.lastSeenAt || new Date().toISOString()
  );
}

/**
 * Strips trailing slashes from a URL string so fetch() calls can safely
 * append paths like "/api/chat" without double-slash issues.
 *
 * @param {*} value - Raw URL string (may be null/undefined).
 * @returns {string} Cleaned URL with no trailing slash.
 */
function cleanBaseUrl(value) {
  return String(value || "").replace(/\/+$/, "");
}

// ─── Encryption ───────────────────────────────────────────────────────────────

/**
 * Encrypts a plaintext API key using AES-256-GCM.
 *
 * Format stored in DB: `{iv_hex}:{auth_tag_hex}:{ciphertext_hex}`
 * A fresh random 12-byte IV is generated per encryption call so two encryptions
 * of the same plaintext produce different ciphertext (semantic security).
 *
 * @param {string} plaintext - The raw API key to encrypt.
 * @returns {string} Encoded string suitable for storage in api_providers.api_key_enc.
 */
function encryptKey(plaintext) {
  const iv = crypto.randomBytes(12);
  const key = crypto.createHash("sha256").update(SECRET_KEY).digest();
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${iv.toString("hex")}:${tag.toString("hex")}:${encrypted.toString("hex")}`;
}

/**
 * Decrypts an AES-256-GCM encrypted API key produced by encryptKey().
 * Returns an empty string (not an exception) on failure so callers always
 * get a string — an invalid/empty key will simply result in a 401 from the
 * provider, which surfaces a clear error to the user.
 *
 * @param {string} stored - The `iv:tag:ciphertext` hex string from the database.
 * @returns {string} Decrypted plaintext API key, or "" on failure.
 */
function decryptKey(stored) {
  try {
    const [ivHex, tagHex, encHex] = stored.split(":");
    const key = crypto.createHash("sha256").update(SECRET_KEY).digest();
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(ivHex, "hex"));
    decipher.setAuthTag(Buffer.from(tagHex, "hex"));
    return decipher.update(Buffer.from(encHex, "hex")) + decipher.final("utf8");
  } catch {
    return "";
  }
}

// ─── Provider call ────────────────────────────────────────────────────────────

/**
 * Makes a non-streaming inference call to any supported provider.
 *
 * Supports four provider types: "ollama", "anthropic", "openai"/"groq"/"custom".
 * Each type uses its own wire protocol:
 *   - Ollama:    POST /api/chat with stream:false
 *   - Anthropic: POST /v1/messages (x-api-key header, separate system field)
 *   - OpenAI/Groq/custom: POST /chat/completions (Authorization: Bearer)
 *
 * @param {{ type: string, base_url: string, api_key_enc: string, name: string }} provider
 *   Provider config row from api_providers (or a synthetic Ollama object).
 * @param {string} modelId - The provider-native model identifier (e.g. "llama3.2:3b").
 * @param {{ role: string, content: string }[]} messages - Conversation turns.
 * @param {{ timeout?: number }} [options={}] - Optional overrides; timeout defaults to 5 minutes.
 * @returns {Promise<{ content: string, tokensUsed: number, providerName: string }>}
 * @throws {Error} With .code "AUTH_ERROR", "RATE_LIMIT", "MODEL_ERROR", or "NETWORK_ERROR".
 */
async function callProvider(provider, modelId, messages, options = {}) {
  const timeout = options.timeout || 300000;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  const apiKey = decryptKey(provider.api_key_enc);
  try {
    let response, data;
    if (provider.type === "ollama") {
      const base = cleanBaseUrl(provider.base_url || OLLAMA_URL);
      response = await fetch(`${base}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: controller.signal,
        body: JSON.stringify({ model: modelId, messages, stream: false, options: { temperature: 0.5, num_predict: 4096 } }),
      });
      if (!response.ok) { const err = new Error(`Ollama ${response.status}`); err.code = response.status === 401 ? "AUTH_ERROR" : "MODEL_ERROR"; throw err; }
      data = await response.json();
      return { content: cleanModelOutput(data.message?.content || data.response || ""), tokensUsed: data.eval_count || 0, providerName: provider.name || "Ollama" };
    } else if (provider.type === "anthropic") {
      const base = cleanBaseUrl(provider.base_url || "https://api.anthropic.com");
      const anthropicMessages = messages.filter(m => m.role !== "system");
      const systemMsg = messages.find(m => m.role === "system");
      response = await fetch(`${base}/v1/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": apiKey },
        signal: controller.signal,
        body: JSON.stringify({ model: modelId, max_tokens: 4096, system: systemMsg?.content || "", messages: anthropicMessages }),
      });
      if (!response.ok) { const err = new Error(`Anthropic ${response.status}`); err.code = response.status === 401 ? "AUTH_ERROR" : response.status === 429 ? "RATE_LIMIT" : "MODEL_ERROR"; throw err; }
      data = await response.json();
      return { content: data.content?.[0]?.text || "", tokensUsed: (data.usage?.input_tokens || 0) + (data.usage?.output_tokens || 0), providerName: "Anthropic" };
    } else if (provider.type === "openai" || provider.type === "groq" || provider.type === "custom") {
      const base = cleanBaseUrl(provider.base_url || (provider.type === "groq" ? "https://api.groq.com/openai" : "https://api.openai.com/v1"));
      const url = provider.type === "openai" || provider.type === "custom" ? `${base}/chat/completions` : `${base}/v1/chat/completions`;
      response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey}` },
        signal: controller.signal,
        body: JSON.stringify({ model: modelId, messages, stream: false }),
      });
      if (!response.ok) { const err = new Error(`Provider ${response.status}`); err.code = response.status === 401 ? "AUTH_ERROR" : response.status === 429 ? "RATE_LIMIT" : "MODEL_ERROR"; throw err; }
      data = await response.json();
      const choice = data.choices?.[0];
      return { content: choice?.message?.content || "", tokensUsed: data.usage?.total_tokens || 0, providerName: provider.name || provider.type };
    }
    throw new Error(`Unknown provider type: ${provider.type}`);
  } catch (err) {
    if (err.name === "AbortError") { const e = new Error("Provider call timed out"); e.code = "NETWORK_ERROR"; throw e; }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Streams an inference response from any supported provider token-by-token.
 *
 * Reads the SSE/NDJSON response body incrementally, calling `onToken` for each
 * piece of generated text, and `onDone` once with the final token count.
 * AbortError (from the client closing the SSE connection) is silently swallowed
 * so the stream gracefully ends without an unhandled rejection.
 *
 * @param {{ type: string, base_url: string, api_key_enc: string }} provider
 * @param {string} modelId - Provider-native model ID.
 * @param {{ role: string, content: string }[]} messages
 * @param {object} options - Reserved for future per-call overrides.
 * @param {(token: string) => void} onToken - Called for each streamed token fragment.
 * @param {(totalTokens: number) => void} onDone - Called once when the stream ends.
 * @param {AbortSignal} signal - Pass req.signal or an AbortController.signal to cancel.
 * @returns {Promise<void>}
 */
async function callProviderStream(provider, modelId, messages, options, onToken, onDone, signal) {
  const apiKey = decryptKey(provider.api_key_enc);
  let totalTokens = 0;
  try {
    let response;
    if (provider.type === "ollama") {
      const base = cleanBaseUrl(provider.base_url || OLLAMA_URL);
      response = await fetch(`${base}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal,
        body: JSON.stringify({ model: modelId, messages, stream: true, options: { temperature: 0.5, num_predict: 4096 } }),
      });
      if (!response.ok) throw new Error(`Ollama ${response.status}`);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split("\n");
        buf = lines.pop();
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line);
            const token = parsed.message?.content || "";
            if (token) { onToken(token); }
            if (parsed.eval_count) totalTokens = parsed.eval_count;
            if (parsed.done) break;
          } catch {}
        }
      }
    } else if (provider.type === "anthropic") {
      const base = cleanBaseUrl(provider.base_url || "https://api.anthropic.com");
      const anthropicMessages = messages.filter(m => m.role !== "system");
      const systemMsg = messages.find(m => m.role === "system");
      response = await fetch(`${base}/v1/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": apiKey },
        signal,
        body: JSON.stringify({ model: modelId, max_tokens: 4096, stream: true, system: systemMsg?.content || "", messages: anthropicMessages }),
      });
      if (!response.ok) throw new Error(`Anthropic ${response.status}`);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split("\n");
        buf = lines.pop();
        for (const line of lines) {
          if (!line.startsWith("data: ")) continue;
          try {
            const parsed = JSON.parse(line.slice(6));
            if (parsed.type === "content_block_delta") { onToken(parsed.delta?.text || ""); }
            if (parsed.type === "message_delta") { totalTokens = parsed.usage?.output_tokens || totalTokens; }
          } catch {}
        }
      }
    } else {
      const base = cleanBaseUrl(provider.base_url || (provider.type === "groq" ? "https://api.groq.com/openai" : "https://api.openai.com/v1"));
      const url = provider.type === "groq" ? `${base}/v1/chat/completions` : `${base}/chat/completions`;
      response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey}` },
        signal,
        body: JSON.stringify({ model: modelId, messages, stream: true }),
      });
      if (!response.ok) throw new Error(`Provider ${response.status}`);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split("\n");
        buf = lines.pop();
        for (const line of lines) {
          if (!line.startsWith("data: ")) continue;
          const payload = line.slice(6).trim();
          if (payload === "[DONE]") break;
          try {
            const parsed = JSON.parse(payload);
            const token = parsed.choices?.[0]?.delta?.content || "";
            if (token) onToken(token);
            if (parsed.usage?.total_tokens) totalTokens = parsed.usage.total_tokens;
          } catch {}
        }
      }
    }
  } catch (err) {
    if (err.name !== "AbortError") throw err;
  }
  onDone(totalTokens);
}

/**
 * Returns the configured Ollama base URL from settings, stripping trailing slashes.
 * Falls back to the OLLAMA_URL constant (from the env var) if not yet configured.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @returns {string} Clean base URL, e.g. "http://host.docker.internal:11434".
 */
function ollamaUrl(db) {
  return cleanBaseUrl(setting(db, "ollamaUrl", OLLAMA_URL));
}

/**
 * Fetches the list of installed models from Ollama's /api/tags endpoint.
 * Times out after 3 seconds so a slow/unreachable Ollama does not block page load.
 *
 * @param {string} [url=OLLAMA_URL] - Ollama base URL.
 * @returns {Promise<Array>} Array of model objects as returned by Ollama (name, size, etc.).
 * @throws {Error} If Ollama is unreachable or returns a non-200 status.
 */
async function fetchOllamaModels(url = OLLAMA_URL) {
  const baseUrl = cleanBaseUrl(url);
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), 3000);
  let response;
  try {
    response = await fetch(`${baseUrl}/api/tags`, { signal: controller.signal });
  } finally {
    clearTimeout(t);
  }
  if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
  const data = await response.json();
  return data.models || [];
}

/**
 * Synchronises the models table with the currently installed Ollama models.
 *
 * Steps:
 *  1. Fetch model list from Ollama /api/tags.
 *  2. For each model, fetch its real context window via /api/show.
 *  3. Upsert the model row (infer capabilities + scores).
 *  4. Mark any previously-seen model not in the new list as "missing".
 *  5. Call ensureDefaultAccess() to create group-level grants if none exist yet.
 *
 * On Ollama unreachable, all local models are marked "missing" so the UI
 * correctly shows no available models rather than stale "available" rows.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @returns {Promise<{ ok: boolean, models: Array, error?: string }>}
 */
async function syncOllamaModels(db) {
  let installed;
  try {
    installed = await fetchOllamaModels(ollamaUrl(db));
  } catch (err) {
    // Ollama unreachable — mark ALL local models as missing so UI reflects reality
    db.prepare("UPDATE models SET status = 'missing' WHERE provider = 'ollama'").run();
    console.error(`[ollama-sync] Unreachable at ${ollamaUrl(db)}: ${err.message}`);
    return { ok: false, models: [], error: err.message };
  }
  const seenIds = [];
  const baseUrl = ollamaUrl(db);
  for (const item of installed) {
    const modelRef = item.name;
    const { speedScore, qualityScore } = inferScores(modelRef, item.size);
    // Fetch real context window from Ollama for this specific model
    const contextSize = await fetchOllamaModelInfo(baseUrl, modelRef);
    const model = {
      id: `ollama:${modelRef}`,
      name: modelRef,
      provider: "ollama",
      modelRef,
      status: "available",
      capabilities: inferCapabilities(modelRef),
      speedScore,
      qualityScore,
      privacy: "local",
      contextSize: contextSize || null,
      lastSeenAt: new Date().toISOString(),
    };
    upsertModel(db, model);
    seenIds.push(model.id);
  }
  if (seenIds.length) {
    const placeholders = seenIds.map(() => "?").join(",");
    db.prepare(`UPDATE models SET status = 'missing' WHERE provider = 'ollama' AND id NOT IN (${placeholders})`).run(...seenIds);
  }
  ensureDefaultAccess(db);
  return { ok: true, models: installed };
}

/**
 * Creates a default "group-all can use every Ollama model" access grant set if no
 * grants exist yet.  This fires once after the very first Ollama sync so new
 * deployments work out of the box without requiring an admin to manually grant access.
 *
 * @param {DatabaseSync} db - Open database handle.
 */
function ensureDefaultAccess(db) {
  const models = db.prepare("SELECT id FROM models WHERE provider = 'ollama'").all();
  const grants = db.prepare("SELECT COUNT(*) AS count FROM access_grants").get().count;
  if (grants > 0 || models.length === 0) return;
  const insert = db.prepare("INSERT INTO access_grants (id, subject_type, subject_id, model_id, can_use) VALUES (?, ?, ?, ?, 1)");
  for (const model of models) {
    insert.run(uid("grant"), "group", "group-all", model.id);
  }
}

/**
 * Convenience wrapper — prepares and runs a SELECT that may return multiple rows.
 * @param {DatabaseSync} db
 * @param {string} query - Parameterised SQL.
 * @param {...*} params - Positional bind parameters.
 * @returns {object[]} Array of row objects.
 */
function rows(db, query, ...params) {
  return db.prepare(query).all(...params);
}

/**
 * Convenience wrapper — prepares and runs a SELECT that returns at most one row.
 * @param {DatabaseSync} db
 * @param {string} query - Parameterised SQL.
 * @param {...*} params - Positional bind parameters.
 * @returns {object|undefined} Single row object or undefined.
 */
function one(db, query, ...params) {
  return db.prepare(query).get(...params);
}

/**
 * Maps a raw models table row to the camelCase object shape used in API responses.
 * Parses the capabilities JSON array and normalises numeric fields.
 *
 * @param {object} row - Raw SQLite row from the models table.
 * @returns {object} Parsed model descriptor.
 */
function parseModel(row) {
  return {
    id: row.id,
    name: row.name,
    provider: row.provider,
    model: row.model_ref,
    status: row.status,
    capabilities: JSON.parse(row.capabilities || "[]"),
    speedScore: row.speed_score,
    qualityScore: row.quality_score,
    privacy: row.privacy,
    lastSeenAt: row.last_seen_at,
    governanceTier: row.governance_tier || "approved-local",
    resourceTier: row.resource_tier || "standard",
    gpuRequired: Boolean(row.gpu_required),
    maxConcurrency: Number(row.max_concurrency || 0),
    maxContextSize: Number(row.max_context_size || row.context_size || 0),
    contextSize: Number(row.context_size || row.max_context_size || 0) || null,
    externalCostTier: row.external_cost_tier || "local-free",
    sensitiveAllowed: row.sensitive_allowed !== 0,
  };
}

/**
 * Maps a raw users table row (with camelCase column aliases) to the safe public
 * user shape sent to clients.  Strips password_hash and normalises types.
 * Returns null if given a falsy row so callers can check for 404 simply.
 *
 * @param {object|null} row - Raw SQLite row (may use snake_case or camelCase aliases).
 * @returns {object|null} Safe public user object.
 */
function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name,
    email: row.email,
    role: row.role,
    rights: safeJson(row.rights, []),
    departmentId: row.departmentId || row.department_id,
    active: row.active,
    employeeId: row.employeeId || row.employee_id || "",
    designation: row.designation || "",
    team: row.team || "",
    branch: row.branch || "",
    manager: row.manager || "",
    organization: row.organization || "Olla Nest",
    aiAccessTier: row.aiAccessTier || row.ai_access_tier || "standard",
    dailyTokenLimit: Number(row.dailyTokenLimit || row.daily_token_limit || 0),
    monthlyTokenLimit: Number(row.monthlyTokenLimit || row.monthly_token_limit || 0),
    gpuQuotaMinutes: Number(row.gpuQuotaMinutes || row.gpu_quota_minutes || 0),
    vramLimitMb: Number(row.vramLimitMb || row.vram_limit_mb || 0),
    concurrentModelLimit: Number(row.concurrentModelLimit || row.concurrent_model_limit || 0),
    apiRateLimitPerMinute: Number(row.apiRateLimitPerMinute || row.api_rate_limit_per_minute || 0),
    maxContextSize: Number(row.maxContextSize || row.max_context_size || 0),
    mfaEnabled: Boolean(row.mfaEnabled || row.mfa_enabled),
    securityRiskScore: Number(row.securityRiskScore || row.security_risk_score || 0),
    accessStatus: row.accessStatus || row.access_status || (row.active ? "active" : "suspended"),
    accessExpiresAt: row.accessExpiresAt || row.access_expires_at || "",
    lastActiveAt: row.lastActiveAt || row.last_active_at || "",
    authProvider: row.authProvider || row.auth_provider || "local",
    phone: row.phone || "",
    avatarInitials: row.avatarInitials || row.avatar_initials || "",
    isEnterprise: (row.authProvider || row.auth_provider || "local") !== "local",
  };
}

/**
 * Parses a JSON string, returning `fallback` instead of throwing on invalid input.
 * Used throughout to safely read JSON columns from SQLite (rights, capabilities, etc.).
 *
 * @param {string|null} value - JSON string to parse.
 * @param {*} fallback - Returned when value is null, empty, or malformed.
 * @returns {*} Parsed value or fallback.
 */
function safeJson(value, fallback) {
  try {
    return JSON.parse(value || "");
  } catch {
    return fallback;
  }
}

const USER_SELECT = `id, name, email, role, rights, department_id AS departmentId, active,
  employee_id AS employeeId, designation, team, branch, manager, organization,
  ai_access_tier AS aiAccessTier, daily_token_limit AS dailyTokenLimit,
  monthly_token_limit AS monthlyTokenLimit, gpu_quota_minutes AS gpuQuotaMinutes,
  vram_limit_mb AS vramLimitMb, concurrent_model_limit AS concurrentModelLimit,
  api_rate_limit_per_minute AS apiRateLimitPerMinute, max_context_size AS maxContextSize,
  mfa_enabled AS mfaEnabled, security_risk_score AS securityRiskScore,
  access_status AS accessStatus, access_expires_at AS accessExpiresAt, last_active_at AS lastActiveAt,
  auth_provider AS authProvider, phone, avatar_initials AS avatarInitials`;

function getUsers(db) {
  return rows(db, `SELECT ${USER_SELECT} FROM users ORDER BY role, name`).map(publicUser);
}

function activeUser(db) {
  const id = setting(db, "activeUserId", "u-admin");
  return publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, id) || one(db, `SELECT ${USER_SELECT} FROM users LIMIT 1`));
}

function userGroupIds(db, userId) {
  return rows(db, "SELECT group_id AS id FROM user_groups WHERE user_id = ?", userId).map((row) => row.id);
}

function roleCatalog(db) {
  return rows(db, "SELECT id, name, description, permissions, system_role AS systemRole FROM role_catalog ORDER BY name").map((role) => ({
    ...role,
    permissions: safeJson(role.permissions, []),
    systemRole: Boolean(role.systemRole),
  }));
}

function permissionCatalog(db) {
  return rows(db, "SELECT key, category, description, risk_level AS riskLevel FROM permission_catalog ORDER BY category, key");
}

function departmentDefaults(departmentId) {
  const defaults = {
    "dept-product": ["chat:use", "models:local:use", "models:coding:use", "workspace:build", "files:upload"],
    "dept-support": ["chat:use", "models:local:use", "models:reasoning:use", "files:upload"],
    "dept-general": ["chat:use", "models:local:use"],
  };
  return defaults[departmentId] || defaults["dept-general"];
}

function userOverrides(db, userId) {
  return rows(db, "SELECT id, user_id AS userId, permission_key AS permissionKey, model_id AS modelId, effect, reason, expires_at AS expiresAt, created_at AS createdAt FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC", userId);
}

/**
 * Computes a user's effective permission set by merging:
 *   1. Explicit rights stored on the user row
 *   2. Department default rights (from deptDefaultRights setting)
 *   3. Role catalog permissions (if user.role matches a role_catalog id)
 *   4. Per-user overrides (allow/deny, respecting expiry times)
 *
 * Deny overrides take final precedence — they are removed from the merged set last.
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @returns {{ permissions: string[], denied: string[], allowedModelIds: string[],
 *             groups: string[], sourcePriority: string[], quotas: object }}
 */
function effectiveAccess(db, user) {
  const role = one(db, "SELECT permissions FROM role_catalog WHERE id = ?", user.role) || null;
  const permissions = new Set([...(user.rights || []), ...departmentDefaults(user.departmentId), ...safeJson(role?.permissions, [])]);
  const denied = new Set();
  for (const override of userOverrides(db, user.id)) {
    if (override.expiresAt && new Date(override.expiresAt).getTime() < Date.now()) continue;
    if (override.effect === "deny") denied.add(override.permissionKey);
    if (override.effect === "allow") permissions.add(override.permissionKey);
  }
  denied.forEach((permission) => permissions.delete(permission));
  return {
    permissions: Array.from(permissions).sort(),
    denied: Array.from(denied).sort(),
    allowedModelIds: allowedModelIds(db, user),
    groups: userGroupIds(db, user.id),
    sourcePriority: ["user override", "department policy", "role permission", "organization default"],
    quotas: {
      dailyTokenLimit: user.dailyTokenLimit,
      monthlyTokenLimit: user.monthlyTokenLimit,
      gpuQuotaMinutes: user.gpuQuotaMinutes,
      vramLimitMb: user.vramLimitMb,
      concurrentModelLimit: user.concurrentModelLimit,
      apiRateLimitPerMinute: user.apiRateLimitPerMinute,
      maxContextSize: user.maxContextSize,
    },
  };
}

/**
 * Returns the list of model IDs a user is allowed to use.
 *
 * Resolution order:
 *  1. Admins: all non-disabled models.
 *  2. access_grants rows matching user / department / any group the user belongs to.
 *  3. Implicit grants: if user has models:local:use right, grant all available non-API models;
 *     if user has models:external:use, also grant API models.  This avoids requiring an admin
 *     to create explicit grants for every new model when users already have the broad permission.
 *  4. user_overrides: allow/deny individual model IDs (respecting expiry).
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @returns {string[]} Array of allowed model IDs.
 */
function allowedModelIds(db, user) {
  if (user.role === "admin") {
    return rows(db, "SELECT id FROM models WHERE status != 'disabled'").map((row) => row.id);
  }
  const groupIds = userGroupIds(db, user.id);
  const subjects = [
    ["user", user.id],
    ["department", user.departmentId],
    ...groupIds.map((id) => ["group", id]),
  ];
  const ids = new Set();
  const stmt = db.prepare("SELECT model_id FROM access_grants WHERE subject_type = ? AND subject_id = ? AND can_use = 1");
  for (const [type, id] of subjects) {
    stmt.all(type, id).forEach((row) => ids.add(row.model_id));
  }

  // If user has models:local:use (or models:manage) via their rights OR department defaults,
  // grant access to all available local (non-API) models.
  // This ensures newly created users don't get "No approved active model" just because
  // no explicit access_grants rows exist for them yet.
  const effectiveRights = new Set([
    ...(user.rights || []),
    ...departmentDefaults(user.departmentId),
  ]);
  if (effectiveRights.has("models:local:use") || effectiveRights.has("models:manage") || effectiveRights.has("models:external:use")) {
    rows(db, "SELECT id FROM models WHERE provider != 'api' AND status != 'disabled'")
      .forEach(row => ids.add(row.id));
  }
  if (effectiveRights.has("models:external:use") || effectiveRights.has("api:use")) {
    rows(db, "SELECT id FROM models WHERE provider = 'api' AND status != 'disabled'")
      .forEach(row => ids.add(row.id));
  }

  for (const override of userOverrides(db, user.id)) {
    if (!override.modelId) continue;
    if (override.expiresAt && new Date(override.expiresAt).getTime() < Date.now()) continue;
    if (override.effect === "allow") ids.add(override.modelId);
    if (override.effect === "deny") ids.delete(override.modelId);
  }
  return Array.from(ids);
}

function allowedModels(db, user) {
  const ids = new Set(allowedModelIds(db, user));
  const allowApi = setting(db, "allowApiModels", false);
  return rows(db, "SELECT * FROM models WHERE status = 'available' OR status = 'configured'")
    .map(parseModel)
    .filter((model) => ids.has(model.id) && (model.provider !== "api" || allowApi));
}

/**
 * Classifies a user's message into a set of capability tags used by the Auto Router
 * to match against model capabilities.
 *
 * Tags are produced by regex pattern matching on the message text and the `mode`
 * string (e.g. mode="build" always adds the "coding" tag).  If no patterns match,
 * ["general", "ask"] are returned as defaults so there is always at least one tag.
 *
 * @param {string} message - The raw user message.
 * @param {string} [mode="ask"] - Chat mode (ask|build|review|fix|learn|debug|test|docs|plan).
 * @returns {string[]} Array of capability tag strings.
 */
function classifyRequest(message, mode = "ask") {
  const text = message.toLowerCase();
  const tags = new Set();
  if (/(medical|health|doctor|patient|clinical|medicine|diagnosis|symptom)/.test(text)) ["medical", "analysis"].forEach((t) => tags.add(t));
  if (/(ocr|image|scan|receipt|invoice|extract text|read this image|document image)/.test(text)) ["ocr", "vision", "document"].forEach((t) => tags.add(t));
  if (/(bug|error|fix|stack|trace|failing|broken|crash|exception)/.test(text) || mode === "fix") ["fix", "debugging", "coding"].forEach((t) => tags.add(t));
  if (/(debug|root cause|why is|not working|wrong output|unexpected)/.test(text) || mode === "debug") ["debugging", "coding", "fix"].forEach((t) => tags.add(t));
  if (/(code|repo|component|api|server|frontend|backend|function|build|generate|create|write a)/.test(text) || mode === "build") ["coding", "build", "project"].forEach((t) => tags.add(t));
  if (/(test|unit test|spec|jest|pytest|coverage|mock|assertion)/.test(text) || mode === "test") ["coding", "build", "review"].forEach((t) => tags.add(t));
  if (/(readme|documentation|doc|comment|jsdoc|api doc|usage|changelog)/.test(text) || mode === "docs") ["writing", "summary", "review"].forEach((t) => tags.add(t));
  if (/(plan|architect|design|schema|structure|roadmap|stack|phase|milestone|breakdown)/.test(text) || mode === "plan") ["review", "analysis", "project"].forEach((t) => tags.add(t));
  if (/(review|risk|issue|security|quality|audit)/.test(text) || mode === "review") ["review", "analysis"].forEach((t) => tags.add(t));
  if (/(write|email|pitch|copy|summarize|summary)/.test(text)) ["writing", "summary"].forEach((t) => tags.add(t));
  if (/(explain|teach|learn|understand|what is|how does)/.test(text) || mode === "learn") ["learn", "general"].forEach((t) => tags.add(t));
  if (!tags.size) ["general", "ask"].forEach((t) => tags.add(t));
  return Array.from(tags);
}

/**
 * Core Auto Router: selects the best model from the user's approved models.
 *
 * Scoring formula per candidate:
 *   score = (capabilityMatch * 35)
 *         + (specialistBonus 45 if request has specialist tag model also has)
 *         + speedScore  * weights.speed
 *         + qualityScore * weights.quality
 *         + privacyScore * weights.privacy    (local=100*w, external=0 or -100)
 *
 * Privacy override: if the message contains sensitive content (PII/API keys/PHI)
 * or the mode is in the "localOnlyModes" list (default: build, fix), all external
 * models are removed from the candidate pool before scoring.
 *
 * If routerEnabled=false, the first allowed model is returned without scoring.
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @param {string} message - User's raw message.
 * @param {string} mode - Chat mode string.
 * @returns {{ selected: object|null, tags: string[], candidates: object[],
 *             reason: string, privacyBlocked: boolean, sensitiveReasons: string[] }}
 */
function routeModel(db, user, message, mode) {
  let candidates = allowedModels(db, user);
  const tags = classifyRequest(message, mode);
  const weights = safeJson(setting(db, "routerWeights", null), { speed: 0.3, quality: 0.5, privacy: 0.2 });
  const localOnlyModes = safeJson(setting(db, "localOnlyModes", null), ["build", "fix"]);
  const sensitivityResult = detectSensitiveContent(message, db);
  const privacyBlocked = sensitivityResult.isSensitive || localOnlyModes.includes(mode);

  if (privacyBlocked) {
    candidates = candidates.filter(m => m.privacy === "local");
  }

  if (!setting(db, "routerEnabled", true)) {
    return {
      selected: candidates[0],
      tags: ["router-disabled"],
      candidates: candidates.map((model) => ({ id: model.id, name: model.name, score: 0 })),
      reason: "Auto Router is disabled by admin, so the first approved model was selected.",
      privacyBlocked,
      sensitiveReasons: sensitivityResult.reasons,
    };
  }

  const scored = candidates.map((model) => {
    const matches = model.capabilities.filter((capability) => tags.includes(capability));
    const capabilityScore = matches.length * 35;
    const specialistScore = ["medical", "ocr", "vision", "coding"].some((tag) => tags.includes(tag) && model.capabilities.includes(tag)) ? 45 : 0;
    const speedW = Number(weights.speed) || 0.3;
    const qualityW = Number(weights.quality) || 0.5;
    const privacyW = Number(weights.privacy) || 0.2;
    const privacyScore = model.privacy === "local" ? 100 * privacyW : setting(db, "allowApiModels", false) ? 0 : -100;
    const score = capabilityScore + specialistScore + model.speedScore * speedW + model.qualityScore * qualityW + privacyScore;
    return { model, score: Math.round(score), matches, breakdown: { capabilityMatch: capabilityScore, speedScore: model.speedScore * speedW, qualityScore: model.qualityScore * qualityW, privacyScore, weightedTotal: score } };
  });

  scored.sort((a, b) => b.score - a.score);
  const selected = scored[0]?.model;
  return {
    selected,
    tags,
    candidates: scored.map((item) => ({ id: item.model.id, name: item.model.name, score: item.score, matches: item.matches, breakdown: item.breakdown })),
    reason: selected
      ? `Selected ${selected.name} by matching request capabilities, speed, quality, privacy, and the user's approved access.`
      : "No approved active model is available for this user.",
    privacyBlocked,
    sensitiveReasons: sensitivityResult.reasons,
  };
}

async function ollamaGenerate(db, model, prompt) {
  const provider = { type: "ollama", base_url: ollamaUrl(db), api_key_enc: encryptKey(""), name: "Ollama" };
  const messages = [{ role: "user", content: prompt }];
  const result = await callProvider(provider, model, messages, { timeout: 300000 });
  return result.content;
}

/**
 * Resolves the provider configuration object for a given router result.
 * For Ollama models, synthesises a provider object from the configured ollamaUrl.
 * For external models, looks up the api_providers row and validates it is enabled.
 *
 * @param {DatabaseSync} db
 * @param {{ selected: { provider: string } }} route - Result of routeModel().
 * @returns {object} Provider config object ready to pass to callProvider/callProviderStream.
 * @throws {Error} If the provider is not configured or is disabled.
 */
function resolveProvider(db, route) {
  if (route.selected.provider === "ollama") {
    return { type: "ollama", base_url: ollamaUrl(db), api_key_enc: encryptKey(""), name: "Ollama" };
  }
  const p = db.prepare("SELECT * FROM api_providers WHERE id = ?").get(route.selected.provider);
  if (!p) throw new Error(`Provider '${route.selected.provider}' is not configured. Add it in Admin → Providers.`);
  if (!p.enabled) throw new Error(`Provider '${p.name}' is disabled. Enable it in Admin → Providers.`);
  return p;
}

/**
 * Strips internal chain-of-thought `<think>…</think>` blocks from model output.
 * Some reasoning models (DeepSeek R1, QwQ) emit these blocks before their final
 * answer — they are not useful to users and should not appear in the chat UI.
 * If a `<think>` block is unclosed, the function falls back to finding the first
 * recognisable code/HTML/React marker and returns everything from that point.
 *
 * @param {string} content - Raw model response string.
 * @returns {string} Cleaned response with think blocks removed.
 */
function cleanModelOutput(content) {
  let output = String(content || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/^\s*<\/think>\s*/i, "")
    .trim();
  if (/^<think>/i.test(output)) {
    const lower = output.toLowerCase();
    const markers = ["```", "<!doctype html", "<html", "import react", "export default"].map((marker) => lower.indexOf(marker)).filter((index) => index > 0);
    output = markers.length ? output.slice(Math.min(...markers)).trim() : output.replace(/^<think>/i, "").trim();
  }
  return output;
}

function listWorkspaceFiles(workspaceRoot, maxFiles = 50) {
  try {
    const results = [];
    function walk(dir, rel) {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.name.startsWith(".")) continue;
        const relPath = rel ? `${rel}/${entry.name}` : entry.name;
        if (entry.isDirectory()) {
          walk(path.join(dir, entry.name), relPath);
        } else {
          results.push(relPath);
          if (results.length >= maxFiles) return;
        }
      }
    }
    walk(workspaceRoot, "");
    return results;
  } catch {
    return [];
  }
}

// ─── Context window helpers ───────────────────────────────────────────────────

/**
 * Rough token count estimate: ~4 characters per token (industry rule of thumb for
 * English text with common code).  Used for context budget calculations where an
 * exact BPE tokeniser is unavailable and speed matters more than precision.
 *
 * @param {string} text
 * @returns {number} Estimated token count.
 */
function estimateTokens(text) {
  return Math.ceil((text || "").length / 4);
}

// Fetch real context window from Ollama /api/show — works for any model
async function fetchOllamaModelInfo(baseUrl, modelName) {
  try {
    const res = await fetch(`${cleanBaseUrl(baseUrl)}/api/show`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: modelName }),
      signal: AbortSignal.timeout(3000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    // modelinfo keys vary by architecture: llama.context_length, gemma3.context_length, etc.
    const info = data.modelinfo || {};
    const ctxKey = Object.keys(info).find(k => k.endsWith(".context_length"));
    if (ctxKey && info[ctxKey] > 0) return Number(info[ctxKey]);
    // Fallback: check parameters string for num_ctx
    const params = String(data.parameters || "");
    const m = params.match(/num_ctx\s+(\d+)/);
    if (m) return Number(m[1]);
    return null;
  } catch {
    return null;
  }
}

/**
 * Returns the context window size for a model in tokens.
 * Checks the main models table first (Ollama models populated from /api/show),
 * then the api_models table (external providers), then defaults to 8192.
 *
 * @param {DatabaseSync} db
 * @param {string} modelName - Model name or model_ref string.
 * @returns {number} Context window in tokens.
 */
function getModelContextWindow(db, modelName) {
  // Check main models table first (Ollama models have context_size from /api/show)
  const row = db.prepare("SELECT context_size FROM models WHERE model_ref = ? OR name = ? LIMIT 1").get(modelName, modelName);
  if (row?.context_size && row.context_size > 0) return row.context_size;
  // Fall back to api_models table (external provider models)
  const apiRow = db.prepare("SELECT context_window FROM api_models WHERE model_id = ? LIMIT 1").get(modelName);
  if (apiRow?.context_window && apiRow.context_window > 0) return apiRow.context_window;
  return 8192;
}

/**
 * Builds the messages array to send to a provider, honouring the model's context window.
 *
 * Sliding-window algorithm (newest-first trimming):
 *   Step 1: Look up the model's actual context window from the DB (default 8192 tokens).
 *   Step 2: Reserve tokens for the system prompt, the new user message, and 512 tokens
 *           of response headroom.  What remains is the `budget` for history.
 *   Step 3: Load all prior messages for the session, oldest → newest.
 *   Step 4: Walk the history from newest → oldest, keeping messages that fit in
 *           the remaining budget.  Stop as soon as adding the next message would
 *           exceed the budget.  This preserves the most recent context.
 *   Step 5: Re-reverse the kept messages back to chronological order.
 *   Step 6: Assemble final array: [system, ...kept history, new user message].
 *
 * Images are attached to the final user message (Ollama multimodal format).
 *
 * @param {DatabaseSync} db
 * @param {string} sessionId - chat_sessions.id for the current conversation.
 * @param {string} systemPrompt - System prompt string.
 * @param {string} userMessage - The new user message (not yet in DB).
 * @param {string} modelName - Used to look up context window size.
 * @param {string[]|null} images - Base64 image strings for multimodal requests.
 * @returns {{ role: string, content: string }[]} Messages array ready for the provider.
 */
function buildContextMessages(db, sessionId, systemPrompt, userMessage, modelName, images) {
  const tokenLimit = getModelContextWindow(db, modelName);
  const reservedTokens = estimateTokens(systemPrompt) + estimateTokens(userMessage) + 512;
  let budget = Math.max(0, tokenLimit - reservedTokens);

  // Load all prior messages oldest→newest (current message not yet inserted)
  const history = db.prepare(
    "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC"
  ).all(sessionId);

  // Walk newest→oldest, keep pairs that fit in budget
  const kept = [];
  for (let i = history.length - 1; i >= 0; i--) {
    const tokens = estimateTokens(history[i].content);
    if (budget - tokens < 0) break;
    budget -= tokens;
    kept.unshift(history[i]);
  }

  const userMsg = images && images.length
    ? { role: "user", content: userMessage, images }
    : { role: "user", content: userMessage };

  return [
    { role: "system", content: systemPrompt },
    ...kept.map(m => ({ role: m.role, content: m.content })),
    userMsg,
  ];
}

/**
 * Constructs the system prompt for a given chat request.
 *
 * The prompt always includes:
 *   - Platform identity ("You are Olla Nest")
 *   - Selected model name and routing reason (so the model doesn't answer with only its name)
 *   - An explicit instruction to suppress <think> blocks
 *
 * If the user has a workspace configured, the prompt also includes:
 *   - Active project folder path
 *   - Write permission mode
 *   - Instruction to use ```lang:filename fence format for saved files
 *   - Current project file list (up to 50 files)
 *
 * A mode-specific instruction is appended last (e.g. "build" instructs returning
 * a complete runnable file, "debug" instructs showing the root cause).
 *
 * @param {string} mode - Chat mode string.
 * @param {{ selected: { name: string }, reason: string }} route - Router result.
 * @param {{ workspaceRoot?: string, permissionMode?: string }|null} workspace
 * @returns {string} Full system prompt.
 */
function buildSystemPrompt(mode, route, workspace) {
  const base = [
    "You are Olla Nest, a company AI workspace assistant.",
    "Answer the user's request directly and completely.",
    "Do not answer with only the selected model name.",
    "Do not include hidden thinking, <think> blocks, or internal reasoning traces.",
    `Selected model route: ${route.selected?.name || "auto"}.`,
    `Routing reason: ${route.reason}`,
  ];
  if (workspace?.workspaceRoot) {
    base.push(`Active project folder: ${workspace.workspaceRoot}`);
    base.push(`Write permission mode: ${workspace.permissionMode || "default"}`);
    base.push("When building, fixing, or generating files, treat the active project folder as the working directory.");
    base.push("IMPORTANT: When generating code files, always specify the filename in the code fence header using the format: ```language:filename.ext — for example: ```html:index.html or ```jsx:src/App.jsx or ```css:styles.css. Use relative paths from the project root. This allows files to be saved directly to the workspace.");
    const files = listWorkspaceFiles(workspace.workspaceRoot);
    if (files.length > 0) {
      base.push(`Current project files:\n${files.map(f => `  ${f}`).join("\n")}`);
    } else {
      base.push("Current project files: (empty — this is a new project)");
    }
  }
  const modeInstructions = {
    ask: "Give a clear, useful answer with enough detail to be acted on.",
    build: "Build the requested output. Return the implementation as one complete, runnable file in a fenced code block. For UI pages, prefer a complete React JSX component when React is implied, otherwise a complete HTML file with embedded CSS and JavaScript. Do not return only a plan.",
    review: "Review the request for issues, risks, improvements, and missing pieces. Lead with actionable findings.",
    fix: "Diagnose the problem and provide the fix with exact steps or code changes. Be specific.",
    learn: "Teach the concept clearly with examples and simple explanation.",
    debug: "Identify the root cause of the error or unexpected behavior. Show the exact line or logic that is wrong, explain why it fails, then provide a specific corrected version. Include a checklist of other things to verify.",
    test: "Write comprehensive tests for the provided code or feature. Include unit tests, edge cases, and error cases. Use the most appropriate test framework for the language. Add brief comments explaining what each test covers.",
    docs: "Generate complete documentation for the provided code, feature, or project. Include: purpose, parameters or props, return values, usage examples, and any important notes. For a project, write a professional README with setup, usage, and API reference sections.",
    plan: "Break this down into a clear implementation plan. Include: recommended tech stack with reasoning, folder/file structure, step-by-step build order, key decisions the developer needs to make, and estimated complexity per phase. Be opinionated and specific.",
  };
  return `${base.join("\n")}\nMode: ${mode}\nInstruction: ${modeInstructions[mode] || modeInstructions.ask}`;
}

function modelPrompt(message, mode, route, workspace) {
  return `${buildSystemPrompt(mode, route, workspace)}\n\nUser request:\n${message.trim()}`;
}

function slugify(value, fallback = "artifact") {
  const slug = String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return slug || fallback;
}

function artifactBaseName(message) {
  const text = String(message || "").toLowerCase();
  if (/sign[\s-]?in|login/.test(text)) return "signin-page";
  if (/dashboard/.test(text)) return "dashboard";
  if (/landing/.test(text)) return "landing-page";
  if (/component/.test(text)) return "component";
  return slugify(text.split(/\n/)[0], "generated-output");
}

function extensionForFence(language, content) {
  const lang = String(language || "").toLowerCase();
  if (["jsx", "tsx", "ts", "js", "html", "css", "json", "md"].includes(lang)) return lang;
  if (/^\s*<!doctype html|<html[\s>]/i.test(content)) return "html";
  if (/import\s+React|from\s+['"]react['"]|useState|className=|function\s+[A-Z]\w+|const\s+[A-Z]\w+\s*=/.test(content)) return "jsx";
  return "txt";
}

function extractArtifacts(content, message) {
  const artifacts = [];
  // Match ```lang:filename or ```lang filename or just ```lang
  const fencePattern = /```([a-zA-Z0-9_-]*)(?::([^\s`]+)|[ \t]+filename=["']?([^"'\s`]+)["']?)?\n([\s\S]*?)```/g;
  let match;
  while ((match = fencePattern.exec(content))) {
    const langPart = match[1];
    const filenameFromColon = match[2]; // ```jsx:src/App.jsx
    const filenameFromAttr = match[3];  // ```jsx filename="App.jsx"
    const body = match[4].trim();
    if (!body) continue;
    const parsedFilename = filenameFromColon || filenameFromAttr || null;
    const ext = extensionForFence(langPart, body);
    artifacts.push({ ext, content: body, parsedFilename });
  }
  if (!artifacts.length) {
    const htmlMatch = String(content).match(/(?:<!doctype html>\s*)?<html[\s\S]*?<\/html>/i);
    if (htmlMatch) artifacts.push({ ext: "html", content: htmlMatch[0].trim(), parsedFilename: null });
  }
  if (!artifacts.length && /<!doctype html|<html[\s>]|import React|from\s+['"]react['"]|useState|className=|function\s+\w+|const\s+\w+\s*=/.test(content)) {
    const ext = extensionForFence("", content);
    artifacts.push({ ext, content: content.trim(), parsedFilename: null });
  }
  const baseName = artifactBaseName(message);
  return artifacts.map((artifact, index) => {
    const name = artifact.parsedFilename
      ? artifact.parsedFilename
      : `${baseName}${artifacts.length > 1 ? `-${index + 1}` : ""}.${artifact.ext}`;
    return { ...artifact, name };
  });
}

function workspaceRoot(db) {
  const configured = setting(db, "workspaceRoot", DEFAULT_WORKSPACE_ROOT);
  return path.resolve(String(configured || DEFAULT_WORKSPACE_ROOT));
}

function normalizePermissionMode(mode) {
  return ["default", "review", "full"].includes(mode) ? mode : "default";
}

function workspaceForUser(db, userId) {
  const prefs = db.prepare("SELECT workspace_root, permission_mode FROM workspace_prefs WHERE user_id = ?").get(userId);
  const root = path.resolve(String(prefs?.workspace_root || workspaceRoot(db)));
  return {
    workspaceRoot: root,
    outputFolder: path.join(root, "olla-nest-output"),
    permissionMode: normalizePermissionMode(prefs?.permission_mode || setting(db, "localPermissionMode", "default")),
    localWritesEnabled: setting(db, "localWritesEnabled", true),
  };
}

/**
 * Extracts code artifacts from a model response and writes them to the user's
 * workspace folder on disk.
 *
 * Path traversal is prevented by checking that every resolved path starts with
 * the workspace root + path separator before writing.
 *
 * Returns an empty array if localWritesEnabled=false or no code blocks found.
 *
 * @param {DatabaseSync} db
 * @param {{ workspaceRoot: string }} workspace - Workspace descriptor from workspaceForUser().
 * @param {string} message - Original user message (for artifact base-name generation).
 * @param {string} mode - Chat mode.
 * @param {string} content - Full model response.
 * @returns {{ name: string, path: string, relativePath: string, bytes: number }[]}
 */
function writeLocalArtifacts(db, workspace, message, mode, content) {
  if (!setting(db, "localWritesEnabled", true)) return [];
  const artifacts = extractArtifacts(content, message);
  if (!artifacts.length) return [];
  const root = path.resolve(String(workspace?.workspaceRoot || workspaceRoot(db)));
  return artifacts.map((artifact) => {
    const filePath = path.resolve(path.join(root, artifact.name));
    // Prevent path traversal outside workspace root
    if (!filePath.startsWith(root + path.sep) && filePath !== root) {
      return null;
    }
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, `${artifact.content}\n`, "utf8");
    return {
      name: artifact.name,
      path: filePath,
      relativePath: path.relative(root, filePath),
      bytes: Buffer.byteLength(artifact.content, "utf8"),
    };
  }).filter(Boolean);
}

/**
 * Appends an event to the audit_events table.
 * Opens its own DB connection so it can be called from any context without
 * requiring the caller to pass a handle.  Errors are silently swallowed —
 * an audit write failure must never crash the main request.
 *
 * @param {string} actor - Human-readable name of the actor (usually user.name).
 * @param {string} action - Dot-separated action key (e.g. "chat.stream").
 * @param {string|null} detail - Short description of what happened.
 * @param {object} [extra={}] - Additional structured data stored as JSON.
 */
function appendAudit(actor, action, detail, extra = {}) {
  try {
    const db = openSql();
    try {
      db.prepare("INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(
        uid("audit"), actor, action, detail || "", JSON.stringify(extra), new Date().toISOString()
      );
    } finally { db.close(); }
  } catch {}
}

/**
 * Records a router_traces row for observability and debugging.
 * If a db handle is provided it is reused; otherwise a new connection is opened.
 * Errors are silently swallowed — trace failures must never break the chat flow.
 *
 * @param {{ userId, sessionId, message, mode, selectedModelId, tags, candidates, live }} trace
 * @param {DatabaseSync|null} [db] - Optional open handle (avoids extra open/close).
 */
function appendTrace(trace, db) {
  try {
    const run = (d) => d.prepare(
      "INSERT INTO router_traces (id, user_id, session_id, message, mode, selected_model_id, tags_json, candidates_json, live, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ).run(uid("trace"), trace.userId || null, trace.sessionId || null, trace.message || "", trace.mode || "",
      trace.selectedModelId || null, JSON.stringify(trace.tags || []), JSON.stringify(trace.candidates || []),
      trace.live ? 1 : 0, new Date().toISOString());
    if (db) { run(db); } else { const d = openSql(); try { run(d); } finally { d.close(); } }
  } catch {}
}

// ─── SQL Chat helpers ─────────────────────────────────────────────────────────
function parseMessage(row) {
  return {
    id: row.id,
    role: row.role,
    content: row.content,
    mode: row.mode || undefined,
    modelId: row.model_id || undefined,
    modelName: row.model_name || undefined,
    routeReason: row.route_reason || undefined,
    live: row.live !== 0,
    artifacts: safeJson(row.artifacts_json, []),
    extractedFiles: safeJson(row.extracted_files_json, []),
    createdAt: row.created_at,
  };
}

function buildChatObject(db, session) {
  const msgs = db.prepare("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC").all(session.id);
  return {
    id: session.id,
    userId: session.user_id,
    title: session.title,
    pinned: Boolean(session.pinned),
    archived: Boolean(session.archived),
    unread: Boolean(session.unread),
    isActive: Boolean(session.is_active),
    messages: msgs.map(parseMessage),
    createdAt: session.created_at,
    updatedAt: session.updated_at,
  };
}

/**
 * Returns the most recently updated active chat session for a user, or undefined.
 * "Active" means is_active=1 — it is the current open thread.
 *
 * @param {DatabaseSync} db
 * @param {string} userId
 * @returns {object|undefined} chat_sessions row or undefined.
 */
function getActiveChatSession(db, userId) {
  return db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1").get(userId);
}

function getActiveChat(db, userId) {
  let session = getActiveChatSession(db, userId);
  if (!session) {
    const now = new Date().toISOString();
    const sessionId = uid("chat");
    db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)").run(sessionId, userId, "New Chat", now, now);
    db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_name, live, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)").run(
      uid("msg"), sessionId, "assistant", "Ready. Ask once, and Auto Router will choose the best approved model for your task.", "Olla Nest", now
    );
    session = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(sessionId);
  }
  return buildChatObject(db, session);
}

function archiveCurrentChat(db, userId) {
  const session = getActiveChatSession(db, userId);
  if (!session) return;
  const hasUserMsg = db.prepare("SELECT id FROM chat_messages WHERE session_id = ? AND role = 'user' LIMIT 1").get(session.id);
  if (!hasUserMsg) return;
  db.prepare("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?").run(new Date().toISOString(), session.id);
}

function chatFor(userId) {
  const docs = readDocs();
  if (!docs.chats[userId]) {
    docs.chats[userId] = {
      id: uid("chat"),
      userId,
      title: "New Chat",
      messages: [
        {
          role: "assistant",
          content: "Ready. Ask once, and Auto Router will choose the best approved model for your task.",
          modelName: "Olla Nest",
          createdAt: new Date().toISOString(),
        },
      ],
    };
    writeDocs(docs);
  }
  return docs.chats[userId];
}

function settingsState(db) {
  return {
    routerEnabled: setting(db, "routerEnabled", true),
    allowApiModels: setting(db, "allowApiModels", false),
    localOnlyDefault: setting(db, "localOnlyDefault", true),
    localWritesEnabled: setting(db, "localWritesEnabled", true),
    workspaceRoot: setting(db, "workspaceRoot", DEFAULT_WORKSPACE_ROOT),
    localPermissionMode: setting(db, "localPermissionMode", "default"),
    ollamaUrl: ollamaUrl(db),
    apiModelProvider: setting(db, "apiModelProvider", "not-configured"),
    anthropicEnabled: Boolean(setting(db, "anthropicEnabled", false)),
    anthropicApiKey: setting(db, "anthropicApiKey", "") ? "set" : "",
    anthropicBaseUrl: setting(db, "anthropicBaseUrl", ""),
    openaiEnabled: Boolean(setting(db, "openaiEnabled", false)),
    openaiApiKey: setting(db, "openaiApiKey", "") ? "set" : "",
    openaiBaseUrl: setting(db, "openaiBaseUrl", ""),
    groqEnabled: Boolean(setting(db, "groqEnabled", false)),
    groqApiKey: setting(db, "groqApiKey", "") ? "set" : "",
    customEnabled: Boolean(setting(db, "customEnabled", false)),
    customName: setting(db, "customName", ""),
    customApiKey: setting(db, "customApiKey", "") ? "set" : "",
    customBaseUrl: setting(db, "customBaseUrl", ""),
    routerWeights: safeJson(setting(db, "routerWeights", null), { speed: 0.3, quality: 0.5, privacy: 0.2 }),
    sensitivePatterns: safeJson(setting(db, "sensitivePatterns", null), []),
    localOnlyModes: safeJson(setting(db, "localOnlyModes", null), ["build", "fix"]),
  };
}

// ─── Sensitive content detection ───────────────────────────────────────────────
/**
 * Scans a message for sensitive content that should never be routed to an external provider.
 *
 * Built-in patterns detect: SSNs, credit card numbers, API key prefixes, and medical/PHI terms.
 * Admins can extend this list via the sensitivePatterns setting (an array of regex strings).
 *
 * @param {string} text - The message to scan.
 * @param {DatabaseSync|null} db - Optional open handle for reading admin patterns.
 * @returns {{ isSensitive: boolean, reasons: string[] }}
 */
function detectSensitiveContent(text, db) {
  const reasons = [];
  const builtinPatterns = [
    { pattern: /\b\d{3}-\d{2}-\d{4}\b/, label: "SSN" },
    { pattern: /\b\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}\b/, label: "credit card" },
    { pattern: /sk-[a-zA-Z0-9]{20,}/, label: "API key" },
    { pattern: /diagnosis|prescription|patient\s+record|PHI|HIPAA/i, label: "medical/PHI" },
  ];
  for (const { pattern, label } of builtinPatterns) {
    if (pattern.test(text)) reasons.push(label);
  }
  try {
    const adminPatterns = db ? safeJson(setting(db, "sensitivePatterns", null), []) : [];
    for (const pat of adminPatterns) {
      try {
        if (new RegExp(pat).test(text)) reasons.push(`admin pattern: ${pat}`);
      } catch {}
    }
  } catch {}
  return { isSensitive: reasons.length > 0, reasons };
}

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

// [POST] /api/auth/login — Auth: public — Purpose: authenticate with email+password; sets HttpOnly session cookie
app.post("/api/auth/login", (req, res) => {
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
app.post("/api/auth/logout", (req, res) => {
  if (req.headers["x-requested-with"] !== "XMLHttpRequest") {
    return res.status(403).json({ error: "Forbidden" });
  }
  const token = parseCookies(req).olla_nest_session;
  if (token) sessions.delete(token);
  res.setHeader("Set-Cookie", "olla_nest_session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
  res.json({ ok: true });
});

// [GET] /api/auth/me — Auth: public — Purpose: check if the browser has a valid session; used on page load
app.get("/api/auth/me", (req, res) => {
  const user = sessionUser(req);
  res.json({ authenticated: Boolean(user), user });
});

// [GET] /api/bootstrap — Auth: public — Purpose: return default admin credentials hint on first boot only
app.get("/api/bootstrap", (req, res) => {
  // Only expose admin email+password hint on first-boot (when default password is still in use)
  const db = openSql();
  try {
    const admin = one(db, "SELECT password_hash FROM users WHERE id = 'u-admin'");
    const isDefaultPassword = admin && bcrypt.compareSync(DEFAULT_ADMIN_PASSWORD, admin.password_hash);
    if (isDefaultPassword) {
      return res.json({ ready: true, adminEmail: DEFAULT_ADMIN_EMAIL, adminPassword: DEFAULT_ADMIN_PASSWORD });
    }
    res.json({ ready: true });
  } finally {
    db.close();
  }
});

// [POST] /api/account/password — Auth: requireAuth — Purpose: self-service password change (requires current password)
app.post("/api/account/password", requireAuth, (req, res) => {
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
app.patch("/api/account/profile", requireAuth, (req, res) => {
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
app.get("/api/account/profile", requireAuth, (req, res) => {
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
app.get("/api/account/usage", requireAuth, (req, res) => {
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

// [GET] /api/state — Auth: requireAuth — Purpose: return the full app state (user, models, chats, settings, access)
//   in one call so the SPA can hydrate with a single fetch on load
app.get("/api/state", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    setSetting(db, "activeUserId", req.user.id);
    // Model state is kept fresh by the background sync timer started at server boot.
    // No sync needed here — just read cached DB state.
    const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
    const models = rows(db, "SELECT * FROM models ORDER BY provider, name").map(parseModel);
    let chats;
    if (user.role === "admin") {
      const sessions = db.prepare("SELECT * FROM chat_sessions WHERE is_active = 1 ORDER BY updated_at DESC").all();
      chats = sessions.map(s => buildChatObject(db, s));
    } else {
      // Ensure an active session exists (creates one if the user has none)
      getActiveChat(db, user.id);
      // Return ALL sessions for this user so the sidebar shows full chat history
      const allSessions = db.prepare(
        "SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY pinned DESC, updated_at DESC LIMIT 50"
      ).all(user.id);
      chats = allSessions.map(s => buildChatObject(db, s));
    }
    const auditRows = db.prepare("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT 30").all();
    const audit = auditRows.map(r => ({ id: r.id, actor: r.actor, action: r.action, detail: r.detail, extra: safeJson(r.extra_json, {}), createdAt: r.created_at }));
    res.json({
      activeUser: user,
      users: getUsers(db),
      departments: (() => {
        const depts = rows(db, "SELECT id, name FROM departments ORDER BY name");
        let deptRights = {};
        try { deptRights = JSON.parse(setting(db, "deptDefaultRights", "{}")); } catch {}
        return depts.map(d => ({ ...d, defaultRights: deptRights[d.id] || [] }));
      })(),
      groups: rows(db, "SELECT id, name FROM groups ORDER BY name"),
      teams: rows(db, "SELECT id, name, description FROM teams ORDER BY name"),
      models,
      settings: settingsState(db),
      chats,
      audit,
      allowedModelIds: allowedModelIds(db, user),
      roles: roleCatalog(db),
      permissions: permissionCatalog(db),
      effectiveAccess: effectiveAccess(db, user),
      workspace: workspaceForUser(db, user.id),
    });
  } finally {
    db.close();
  }
});

// [GET] /api/ollama/ping — Auth: requireAuth — Purpose: fast 2-second connectivity check, no DB sync (used by status chip)
app.get("/api/ollama/ping", requireAuth, async (req, res) => {
  const db = openSql();
  const url = cleanBaseUrl(ollamaUrl(db));
  db.close();
  try {
    const r = await fetch(`${url}/api/tags`, { signal: AbortSignal.timeout(2000) });
    res.json({ ok: r.ok });
  } catch {
    res.json({ ok: false });
  }
});

// [GET] /api/ollama/models — Auth: requireAuth — Purpose: trigger an Ollama sync and return result (used by sync button)
app.get("/api/ollama/models", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    const result = await syncOllamaModels(db);
    res.json(result);
  } catch (error) {
    res.json({ ok: false, error: error.message, models: [] });
  } finally {
    db.close();
  }
});

// [GET] /api/admin/ollama/ping — Auth: requireAdmin — Purpose: test raw Ollama connectivity; returns URL tried + model count
app.get("/api/admin/ollama/ping", requireAdmin, async (req, res) => {
  const db = openSql();
  const url = ollamaUrl(db);
  db.close();
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), 10000);
  try {
    const r = await fetch(`${url}/api/tags`, { signal: controller.signal });
    clearTimeout(t);
    const data = await r.json();
    res.json({ ok: r.ok, url, status: r.status, modelCount: (data.models || []).length, models: (data.models || []).map(m => m.name) });
  } catch (err) {
    clearTimeout(t);
    res.json({ ok: false, url, error: err.message });
  }
});

// [GET] /api/admin/sessions/active — Auth: requireAdmin — Purpose: list all active in-memory sessions (for security panel)
app.get("/api/admin/sessions/active", requireAdmin, (req, res) => {
  const list = [];
  for (const [token, s] of sessions) {
    list.push({
      userId: s.user?.id,
      name: s.user?.name,
      email: s.user?.email,
      role: s.user?.role,
      expiresAt: s.expiresAt,
      token: token.slice(0, 8) + "…", // redact
    });
  }
  res.json({ sessions: list });
});

// [DELETE] /api/admin/sessions/user/:userId — Auth: requireAdmin — Purpose: force-logout a specific user
app.delete("/api/admin/sessions/user/:userId", requireAdmin, (req, res) => {
  const { userId } = req.params;
  let count = 0;
  for (const [token, s] of sessions) {
    if (s.user?.id === userId) { sessions.delete(token); count++; }
  }
  res.json({ ok: true, cleared: count });
});

// [GET] /api/admin/departments — Auth: requireAdmin — Purpose: list all departments with their default rights
app.get("/api/admin/departments", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const depts = rows(db, "SELECT id, name FROM departments ORDER BY name");
    // load default rights per dept from settings
    const raw = setting(db, "deptDefaultRights", "{}");
    let deptRights = {};
    try { deptRights = JSON.parse(raw); } catch { deptRights = {}; }
    res.json({ departments: depts.map(d => ({ ...d, defaultRights: deptRights[d.id] || [] })) });
  } finally { db.close(); }
});

// [PATCH] /api/admin/departments/:id/rights — Auth: requireAdmin — Purpose: set default permission rights for a department
app.patch("/api/admin/departments/:id/rights", requireAdmin, (req, res) => {
  if (!req.headers["x-requested-with"]) return res.status(403).json({ error: "Forbidden: missing CSRF header" });
  const db = openSql();
  try {
    const { rights } = req.body;
    const raw = setting(db, "deptDefaultRights", "{}");
    let deptRights = {};
    try { deptRights = JSON.parse(raw); } catch { deptRights = {}; }
    deptRights[req.params.id] = Array.isArray(rights) ? rights : [];
    setSetting(db, "deptDefaultRights", JSON.stringify(deptRights));
    res.json({ ok: true });
  } finally { db.close(); }
});

const MAC_HOME = "/mac-home"; /* bind-mounted from ${HOME} on the host */

// [GET] /api/workspace/browse — Auth: requireAdmin — Purpose: browse host filesystem dirs for workspace folder picker
app.get("/api/workspace/browse", requireAdmin, (req, res) => {
  /* Prefer Mac home if mounted, else fall back to container workspace */
  const defaultHome = fs.existsSync(MAC_HOME) ? MAC_HOME : path.join(DATA_DIR, "workspace");
  fs.mkdirSync(path.join(DATA_DIR, "workspace"), { recursive: true });
  let resolved;
  try {
    const requestedPath = String(req.query.path || defaultHome).trim();
    resolved = path.resolve(requestedPath);
    if (req.query.create === "1") {
      fs.mkdirSync(resolved, { recursive: true });
    }
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
      resolved = defaultHome;
    }
    const entries = fs.readdirSync(resolved, { withFileTypes: true });
    const dirs = entries
      .filter((e) => e.isDirectory() && !e.name.startsWith("."))
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((e) => ({ name: e.name, path: path.join(resolved, e.name) }));
    const parentPath = (resolved === path.parse(resolved).root || resolved === defaultHome) ? null : path.dirname(resolved);
    res.json({ current: resolved, parent: parentPath, dirs, home: defaultHome, macHome: MAC_HOME });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

// [POST] /api/workspace/local-settings — Auth: requireAuth — Purpose: save or clear per-user workspace folder + permission mode
app.post("/api/workspace/local-settings", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const workspaceRootInput = String(req.body.workspaceRoot || "").trim();
    const permissionMode = normalizePermissionMode(req.body.permissionMode);
    if (!workspaceRootInput) {
      db.prepare("DELETE FROM workspace_prefs WHERE user_id = ?").run(req.user.id);
      appendAudit(req.user.name, "workspace.local.clear", "Cleared local workspace folder");
      return res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
    }
    const nextRoot = path.resolve(workspaceRootInput);
    fs.mkdirSync(nextRoot, { recursive: true });
    db.prepare("INSERT OR REPLACE INTO workspace_prefs (user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)").run(
      req.user.id, nextRoot, permissionMode, new Date().toISOString()
    );
    appendAudit(req.user.name, "workspace.local.save", `Updated local workspace folder to ${nextRoot}`, { permissionMode });
    res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
  } finally {
    db.close();
  }
});

// [POST] /api/chat — Auth: requireAuth — Purpose: non-streaming chat request (legacy/fallback); returns full response JSON
app.post("/api/chat", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
    if (!hasRight(user, "chat:use")) return res.status(403).json({ error: "Chat access is not enabled for this account" });
    if (!checkChatRateLimit(req.user.id, user.apiRateLimitPerMinute)) {
      return res.status(429).json({ error: `Rate limit reached. You are limited to ${user.apiRateLimitPerMinute} messages per minute. Please wait before sending another message.` });
    }
    const { message, mode = "ask", manualModelId } = req.body;
    if (!message || !message.trim()) return res.status(400).json({ error: "Message is required" });
    if (String(message).length > 16000) return res.status(400).json({ error: "Message exceeds maximum length of 16,000 characters" });

    const manualModel = manualModelId ? allowedModels(db, user).find((model) => model.id === manualModelId) : null;
    const route = manualModel
      ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model." }
      : routeModel(db, user, message, mode);

    if (!route.selected) return res.status(403).json({ error: route.reason });

    const workspace = workspaceForUser(db, user.id);
    const chat = getActiveChat(db, user.id);
    let content;
    let live = true;
    try {
      const provider = resolveProvider(db, route);
      const systemPrompt = buildSystemPrompt(mode, route, workspace);
      const images = Array.isArray(req.body.images) ? req.body.images : [];
      const contextMsgs = buildContextMessages(db, chat.id, systemPrompt, message, route.selected.model, images);
      const result = await callProvider(provider, route.selected.model, contextMsgs, { timeout: 300000 });
      content = result.content;
    } catch (error) {
      live = false;
      content = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${error.message}\n\nThe route itself is valid; check model availability, startup time, or admin configuration.`;
    }

    const writeApproved = Boolean(req.body.writeToWorkspace) || workspace.permissionMode === "full";
    const shouldWriteLocal = live && workspace.localWritesEnabled && writeApproved;
    const artifacts = shouldWriteLocal ? writeLocalArtifacts(db, workspace, message, mode, content) : [];

    /* Always extract artifact file contents so remote clients can write them locally */
    const extractedFiles = live ? extractArtifacts(content, message).map(a => ({
      name: a.name,
      content: a.content,
    })) : [];

    /* When files are available (saved or extracted), strip code blocks from chat */
    let chatContent = content;
    if (artifacts.length || extractedFiles.length) {
      chatContent = content
        .replace(/```[\s\S]*?```/g, "")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
    }
    const now = new Date().toISOString();
    const userMsgId = uid("msg");
    const asstMsgId = uid("msg");
    db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(userMsgId, chat.id, "user", message, mode, now);
    db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
      asstMsgId, chat.id, "assistant", chatContent, route.selected.id, route.selected.name, route.reason, live ? 1 : 0, JSON.stringify(artifacts), JSON.stringify(extractedFiles), now
    );
    /* Auto-set title from first user message if still default */
    const currentSession = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id);
    let title = currentSession?.title || "New Chat";
    if (!title || title === "New Chat" || title === "New workspace") {
      title = message.slice(0, 45).trim() + (message.length > 45 ? "…" : "");
    }
    db.prepare("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?").run(title, now, chat.id);
    const updatedChat = buildChatObject(db, db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id));
    appendTrace({ userId: user.id, sessionId: chat.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live }, db);
    appendAudit(user.name, "chat.request", `${mode.toUpperCase()} routed to ${route.selected.name}`, { live, artifacts });
    res.json({ content, route, model: route.selected, live, artifacts, extractedFiles, chat: updatedChat });
  } finally {
    db.close();
  }
});

function autoTitle(messages) {
  const first = messages.find(m => m.role === "user");
  if (!first) return "New chat";
  return String(first.content).slice(0, 45).trim() + (first.content.length > 45 ? "…" : "");
}

// [DELETE] /api/chat — Auth: requireAuth — Purpose: permanently delete the current active chat and create a fresh one
app.delete("/api/chat", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const session = getActiveChatSession(db, req.user.id);
    if (session) {
      db.prepare("DELETE FROM chat_messages WHERE session_id = ?").run(session.id);
      db.prepare("DELETE FROM chat_sessions WHERE id = ?").run(session.id);
    }
    getActiveChat(db, req.user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [POST] /api/chat/clear — Auth: requireAuth — Purpose: archive the current chat and start a fresh active session
app.post("/api/chat/clear", requireAuth, (req, res) => {
  const db = openSql();
  try {
    archiveCurrentChat(db, req.user.id);
    getActiveChat(db, req.user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [GET] /api/threads — Auth: requireAuth — Purpose: list user's active thread and full history for the sidebar
app.get("/api/threads", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const activeSessions = db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC").all(user.id);
    const historySessions = db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 0 ORDER BY pinned DESC, updated_at DESC").all(user.id);
    const active = activeSessions.length ? buildChatObject(db, activeSessions[0]) : null;
    const history = historySessions.map(s => buildChatObject(db, s));
    res.json({ active, history });
  } finally {
    db.close();
  }
});

// [DELETE] /api/threads/:id — Auth: requireAuth — Purpose: permanently delete a specific thread (user must own it)
app.delete("/api/threads/:id", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const session = db.prepare("SELECT id FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
    if (!session) return res.status(404).json({ error: "Thread not found" });
    db.prepare("DELETE FROM chat_messages WHERE session_id = ?").run(req.params.id);
    db.prepare("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?").run(req.params.id, user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [PATCH] /api/threads/:id — Auth: requireAuth — Purpose: update thread metadata (title, pinned, archived, unread)
app.patch("/api/threads/:id", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const session = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
    if (!session) return res.status(404).json({ error: "Thread not found" });
    const now = new Date().toISOString();
    if (typeof req.body.title !== "undefined") db.prepare("UPDATE chat_sessions SET title = ? WHERE id = ?").run(req.body.title, req.params.id);
    if (typeof req.body.pinned !== "undefined") db.prepare("UPDATE chat_sessions SET pinned = ? WHERE id = ?").run(req.body.pinned ? 1 : 0, req.params.id);
    if (typeof req.body.archived !== "undefined") db.prepare("UPDATE chat_sessions SET archived = ? WHERE id = ?").run(req.body.archived ? 1 : 0, req.params.id);
    if (typeof req.body.unread !== "undefined") db.prepare("UPDATE chat_sessions SET unread = ? WHERE id = ?").run(req.body.unread ? 1 : 0, req.params.id);
    db.prepare("UPDATE chat_sessions SET updated_at = ? WHERE id = ?").run(now, req.params.id);
    const updated = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(req.params.id);
    res.json({ ok: true, thread: buildChatObject(db, updated) });
  } finally {
    db.close();
  }
});

// [POST] /api/threads/:id/activate — Auth: requireAuth — Purpose: make a history thread the active thread (archives current)
app.post("/api/threads/:id/activate", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const target = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
    if (!target) return res.status(404).json({ error: "Thread not found" });
    archiveCurrentChat(db, user.id);
    db.prepare("UPDATE chat_sessions SET is_active = 1, unread = 0, updated_at = ? WHERE id = ?").run(new Date().toISOString(), req.params.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [POST] /api/threads/:id/fork — Auth: requireAuth — Purpose: duplicate a thread's messages into a new active thread
app.post("/api/threads/:id/fork", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const src = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
    if (!src) return res.status(404).json({ error: "Thread not found" });
    archiveCurrentChat(db, user.id);
    const now = new Date().toISOString();
    const newId = uid("chat");
    db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)").run(
      newId, user.id, `Fork of ${src.title}`, now, now
    );
    const srcMsgs = db.prepare("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC").all(src.id);
    for (const msg of srcMsgs) {
      db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
        uid("msg"), newId, msg.role, msg.content, msg.mode, msg.model_id, msg.model_name, msg.route_reason, msg.live, msg.artifacts_json, msg.extracted_files_json, msg.created_at
      );
    }
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [POST] /api/admin/settings — Auth: requireAdmin — Purpose: update platform settings (router, Ollama URL, API keys, workspace)
app.post("/api/admin/settings", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    ["routerEnabled", "allowApiModels", "localOnlyDefault", "localWritesEnabled", "localPermissionMode", "apiModelProvider",
      "anthropicEnabled", "anthropicApiKey", "anthropicBaseUrl",
      "openaiEnabled", "openaiApiKey", "openaiBaseUrl",
      "groqEnabled", "groqApiKey",
      "customEnabled", "customApiKey", "customBaseUrl", "customName",
    ].forEach((key) => {
      if (typeof req.body[key] !== "undefined") setSetting(db, key, req.body[key]);
    });
    // Router config
    if (typeof req.body.routerWeights !== "undefined") setSetting(db, "routerWeights", JSON.stringify(req.body.routerWeights));
    if (typeof req.body.sensitivePatterns !== "undefined") setSetting(db, "sensitivePatterns", JSON.stringify(req.body.sensitivePatterns));
    if (typeof req.body.localOnlyModes !== "undefined") setSetting(db, "localOnlyModes", JSON.stringify(req.body.localOnlyModes));
    if (typeof req.body.workspaceRoot !== "undefined") {
      const nextRoot = path.resolve(String(req.body.workspaceRoot || DEFAULT_WORKSPACE_ROOT));
      setSetting(db, "workspaceRoot", nextRoot);
      fs.mkdirSync(nextRoot, { recursive: true });
    }
    if (typeof req.body.ollamaUrl !== "undefined") {
      const nextUrl = cleanBaseUrl(req.body.ollamaUrl);
      if (!/^https?:\/\/[^ "]+$/.test(nextUrl)) return res.status(400).json({ error: "Ollama URL must start with http:// or https://" });
      setSetting(db, "ollamaUrl", nextUrl);
    }
    appendAudit(user.name, "admin.settings.save", "Updated system settings");
    res.json({ ok: true, settings: settingsState(db) });
  } finally {
    db.close();
  }
});


// [POST] /api/admin/users — Auth: requireAdmin — Purpose: create a new user account; returns credentials for invite modal
app.post("/api/admin/users", requireAdmin, (req, res) => {
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
app.patch("/api/admin/users/:id", requireAdmin, (req, res) => {
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
    if (typeof role !== "undefined") db.prepare("UPDATE users SET role = ? WHERE id = ?").run(role, req.params.id);
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
app.post("/api/admin/users/:id/reset-password", requireAdmin, (req, res) => {
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
app.get("/api/admin/users/:id/effective-access", requireAdmin, (req, res) => {
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
app.post("/api/admin/users/:id/overrides", requireAdmin, (req, res) => {
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

// ─── Teams CRUD ───────────────────────────────────────────────────────────────
// [GET] /api/admin/teams — Auth: requireAdmin — Purpose: list all teams
app.get("/api/admin/teams", requireAdmin, (req, res) => {
  const db = openSql();
  try { res.json({ teams: rows(db, "SELECT * FROM teams ORDER BY name") }); }
  finally { db.close(); }
});

// [POST] /api/admin/teams — Auth: requireAdmin — Purpose: create a new team (name must be unique)
app.post("/api/admin/teams", requireAdmin, (req, res) => {
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
app.delete("/api/admin/teams/:id", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    db.prepare("DELETE FROM teams WHERE id = ?").run(req.params.id);
    appendAudit(req.user.name, "admin.team.delete", `Deleted team ${req.params.id}`);
    res.json({ ok: true });
  } finally { db.close(); }
});

// [DELETE] /api/admin/overrides/:id — Auth: requireAdmin — Purpose: remove a per-user permission override
app.delete("/api/admin/overrides/:id", requireAdmin, (req, res) => {
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

// [PATCH] /api/admin/models/:id/governance — Auth: requireAdmin — Purpose: update model governance fields (tier, GPU, sensitivity)
app.patch("/api/admin/models/:id/governance", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const model = one(db, "SELECT id FROM models WHERE id = ?", req.params.id);
    if (!model) return res.status(404).json({ error: "Model not found" });
    const fields = {
      status: "status",
      governanceTier: "governance_tier",
      resourceTier: "resource_tier",
      gpuRequired: "gpu_required",
      maxConcurrency: "max_concurrency",
      maxContextSize: "max_context_size",
      externalCostTier: "external_cost_tier",
      sensitiveAllowed: "sensitive_allowed",
    };
    for (const [bodyKey, column] of Object.entries(fields)) {
      if (typeof req.body[bodyKey] === "undefined") continue;
      const value = ["gpuRequired", "sensitiveAllowed"].includes(bodyKey) ? (req.body[bodyKey] ? 1 : 0) : req.body[bodyKey];
      db.prepare(`UPDATE models SET ${column} = ? WHERE id = ?`).run(value, req.params.id);
    }
    appendAudit(req.user.name, "admin.model.governance", `Updated governance for ${req.params.id}`);
    res.json({ ok: true, model: parseModel(one(db, "SELECT * FROM models WHERE id = ?", req.params.id)) });
  } finally {
    db.close();
  }
});

// ─── SSE Streaming ────────────────────────────────────────────────────────────
// [POST] /api/chat/stream — Auth: requireAuth — Purpose: primary chat endpoint; streams tokens via SSE then persists messages
//   SSE event types: "routing" (model chosen), "token" (incremental text), "done" (final metadata), "error"
app.post("/api/chat/stream", requireAuth, async (req, res) => {
  const db = openSql();
  let dbClosed = false;
  const closeDb = () => { if (!dbClosed) { dbClosed = true; try { db.close(); } catch {} } };

  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("X-Accel-Buffering", "no");
  res.flushHeaders();

  const keepAlive = setInterval(() => { res.write(": keepalive\n\n"); }, 15000);

  const abort = new AbortController();
  res.on("close", () => { abort.abort(); clearInterval(keepAlive); });

  try {
    const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
    if (!hasRight(user, "chat:use")) { res.write(`data: ${JSON.stringify({ type: "error", message: "Chat access not enabled" })}\n\n`); return res.end(); }
    if (!checkChatRateLimit(req.user.id, user.apiRateLimitPerMinute)) {
      res.write(`data: ${JSON.stringify({ type: "error", message: `Rate limit reached. You are limited to ${user.apiRateLimitPerMinute} messages per minute.` })}\n\n`);
      return res.end();
    }
    const { message, mode = "ask", manualModelId } = req.body;
    if (!message || !message.trim()) { res.write(`data: ${JSON.stringify({ type: "error", message: "Message is required" })}\n\n`); return res.end(); }
    if (String(message).length > 16000) { res.write(`data: ${JSON.stringify({ type: "error", message: "Message exceeds maximum length of 16,000 characters" })}\n\n`); return res.end(); }

    const manualModel = manualModelId ? allowedModels(db, user).find(m => m.id === manualModelId) : null;
    const route = manualModel
      ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model.", privacyBlocked: false, sensitiveReasons: [] }
      : routeModel(db, user, message, mode);

    if (!route.selected) { res.write(`data: ${JSON.stringify({ type: "error", message: route.reason })}\n\n`); return res.end(); }

    res.write(`data: ${JSON.stringify({ type: "routing", model: route.selected.name, provider: route.selected.provider, reason: route.reason })}\n\n`);

    const workspace = workspaceForUser(db, user.id);
    const images = Array.isArray(req.body.images) ? req.body.images : [];

    // Load session history for context window
    const chatSession = getActiveChat(db, user.id);
    const systemPrompt = buildSystemPrompt(mode, route, workspace);
    const messages = buildContextMessages(db, chatSession.id, systemPrompt, message, route.selected.model, images);

    let fullContent = "";
    let tokensUsed = 0;
    let live = true;
    const startMs = Date.now();

    try {
      const provider = resolveProvider(db, route);
      await callProviderStream(provider, route.selected.model, messages, {}, (token) => {
        fullContent += token;
        res.write(`data: ${JSON.stringify({ type: "token", content: token })}\n\n`);
      }, (total) => { tokensUsed = total; }, abort.signal);
    } catch (err) {
      live = false;
      fullContent = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${err.message}`;
      res.write(`data: ${JSON.stringify({ type: "error", message: err.message })}\n\n`);
    }

    fullContent = cleanModelOutput(fullContent);
    const writeApproved = Boolean(req.body.writeToWorkspace) || workspace.permissionMode === "full";
    const shouldWriteLocal = live && workspace.localWritesEnabled && writeApproved;
    const artifacts = shouldWriteLocal ? writeLocalArtifacts(db, workspace, message, mode, fullContent) : [];
    const extractedFiles = live ? extractArtifacts(fullContent, message).map(a => ({ name: a.name, content: a.content })) : [];
    let chatContent = fullContent;
    if (artifacts.length || extractedFiles.length) {
      chatContent = fullContent.replace(/```[\s\S]*?```/g, "").replace(/\n{3,}/g, "\n\n").trim();
    }

    if (!dbClosed) {
      const chat = chatSession; // already fetched above for context loading
      const now = new Date().toISOString();
      const userMsgId = uid("msg");
      const asstMsgId = uid("msg");
      const latencyMs = Date.now() - startMs;
      db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(userMsgId, chat.id, "user", message, mode, now);
      db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, tokens_used, latency_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
        asstMsgId, chat.id, "assistant", chatContent, route.selected.id, route.selected.name, route.reason, live ? 1 : 0, JSON.stringify(artifacts), JSON.stringify(extractedFiles), tokensUsed, latencyMs, now
      );
      const currentSession = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id);
      let title = currentSession?.title || "New Chat";
      if (!title || title === "New Chat") title = message.slice(0, 45).trim() + (message.length > 45 ? "…" : "");
      db.prepare("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?").run(title, now, chat.id);
      appendTrace({ userId: user.id, sessionId: chat.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live }, db);
      appendAudit(user.name, "chat.stream", `${mode.toUpperCase()} streamed to ${route.selected.name}`, { live });
      res.write(`data: ${JSON.stringify({ type: "done", tokensUsed, latencyMs, messageId: asstMsgId, live, artifacts, extractedFiles })}\n\n`);
      return;
    }

    // DB was closed before we could persist — send done with no messageId so feedback is skipped
    const latencyMs = Date.now() - startMs;
    res.write(`data: ${JSON.stringify({ type: "done", tokensUsed, latencyMs, messageId: null, live: false, artifacts: [], extractedFiles: [] })}\n\n`);
  } catch (err) {
    res.write(`data: ${JSON.stringify({ type: "error", message: err.message })}\n\n`);
  } finally {
    clearInterval(keepAlive);
    closeDb();
    res.end();
  }
});

// ─── Feedback ─────────────────────────────────────────────────────────────────
// [POST] /api/feedback — Auth: requireAuth — Purpose: submit thumbs-up/down rating for an assistant message
app.post("/api/feedback", requireAuth, (req, res) => {
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

// ─── Reports ──────────────────────────────────────────────────────────────────
// [GET] /api/admin/reports — Auth: requireAdmin — Purpose: return aggregated analytics (daily activity, model usage,
//   token leaderboard, mode breakdown, department usage, latency) for the Reports tab
app.get("/api/admin/reports", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const days = Number(req.query.days || 30);
    const since = new Date(Date.now() - days * 86400000).toISOString();

    // 1. Daily message & token activity (last N days)
    const dailyActivity = db.prepare(`
      SELECT substr(created_at,1,10) AS day,
             COUNT(*) AS messages,
             SUM(CASE WHEN role='assistant' THEN tokens_used ELSE 0 END) AS tokens
      FROM chat_messages
      WHERE created_at >= ?
      GROUP BY day ORDER BY day
    `).all(since);

    // 2. Model usage distribution
    const modelUsage = db.prepare(`
      SELECT model_name, COUNT(*) AS uses,
             SUM(tokens_used) AS total_tokens,
             AVG(latency_ms) AS avg_latency
      FROM chat_messages
      WHERE role='assistant' AND model_name IS NOT NULL AND model_name != ''
      GROUP BY model_name ORDER BY uses DESC LIMIT 10
    `).all();

    // 3. Token leaderboard (users ranked by total tokens)
    const tokenLeaderboard = db.prepare(`
      SELECT u.name, u.email, u.role, u.department_id,
             u.ai_access_tier, u.daily_token_limit,
             COUNT(DISTINCT cs.id) AS sessions,
             COUNT(cm.id) AS messages,
             COALESCE(SUM(cm.tokens_used),0) AS total_tokens,
             COALESCE(AVG(cm.tokens_used),0) AS avg_tokens_per_msg,
             MAX(cs.updated_at) AS last_active
      FROM users u
      LEFT JOIN chat_sessions cs ON cs.user_id = u.id
      LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role = 'assistant'
      WHERE u.active = 1
      GROUP BY u.id ORDER BY total_tokens DESC LIMIT 20
    `).all();

    // 4. Mode breakdown (ask / build / fix / review etc.)
    const modeBreakdown = db.prepare(`
      SELECT mode, COUNT(*) AS count
      FROM router_traces WHERE created_at >= ? AND mode IS NOT NULL
      GROUP BY mode ORDER BY count DESC
    `).all(since);

    // 5. Department usage
    const deptUsage = db.prepare(`
      SELECT d.name AS dept,
             COUNT(DISTINCT cs.id) AS sessions,
             COALESCE(SUM(cm.tokens_used),0) AS tokens
      FROM departments d
      LEFT JOIN users u ON u.department_id = d.id
      LEFT JOIN chat_sessions cs ON cs.user_id = u.id AND cs.created_at >= ?
      LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role = 'assistant'
      GROUP BY d.id ORDER BY tokens DESC
    `).all(since);

    // 6. Access tier user distribution
    const tierDist = db.prepare(`
      SELECT ai_access_tier AS tier, COUNT(*) AS count
      FROM users WHERE active = 1
      GROUP BY ai_access_tier ORDER BY count DESC
    `).all();

    // 7. Live vs failed responses
    const liveVsFailed = db.prepare(`
      SELECT SUM(CASE WHEN live=1 THEN 1 ELSE 0 END) AS live_count,
             SUM(CASE WHEN live=0 THEN 1 ELSE 0 END) AS failed_count
      FROM chat_messages WHERE role='assistant' AND created_at >= ?
    `).get(since);

    // 8. Audit event breakdown (top action types)
    const auditBreakdown = db.prepare(`
      SELECT action, COUNT(*) AS count
      FROM audit_events WHERE created_at >= ?
      GROUP BY action ORDER BY count DESC LIMIT 10
    `).all(since);

    // 9. Daily audit events over time
    const auditTimeline = db.prepare(`
      SELECT substr(created_at,1,10) AS day, COUNT(*) AS events
      FROM audit_events WHERE created_at >= ?
      GROUP BY day ORDER BY day
    `).all(since);

    // 10. Response latency by model (avg ms)
    const latencyByModel = db.prepare(`
      SELECT model_name,
             ROUND(AVG(latency_ms)) AS avg_ms,
             MIN(latency_ms) AS min_ms,
             MAX(latency_ms) AS max_ms,
             COUNT(*) AS count
      FROM chat_messages
      WHERE role='assistant' AND model_name IS NOT NULL AND model_name != ''
        AND latency_ms > 0
      GROUP BY model_name ORDER BY avg_ms ASC LIMIT 10
    `).all();

    // Summary stats
    const summary = db.prepare(`
      SELECT COUNT(DISTINCT u.id) AS total_users,
             COUNT(DISTINCT cs.id) AS total_sessions,
             COUNT(cm.id) AS total_messages,
             COALESCE(SUM(cm.tokens_used),0) AS total_tokens,
             ROUND(AVG(cm.latency_ms)) AS avg_latency
      FROM users u
      LEFT JOIN chat_sessions cs ON cs.user_id = u.id
      LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role='assistant'
      WHERE u.active = 1
    `).get();

    res.json({
      summary,
      dailyActivity,
      modelUsage,
      tokenLeaderboard,
      modeBreakdown,
      deptUsage,
      tierDist,
      liveVsFailed: liveVsFailed || { live_count: 0, failed_count: 0 },
      auditBreakdown,
      auditTimeline,
      latencyByModel,
    });
  } finally {
    db.close();
  }
});

// [GET] /api/admin/feedback — Auth: requireAdmin — Purpose: aggregate thumbs ratings per model (model quality scorecard)
app.get("/api/admin/feedback", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const rows = db.prepare(`
      SELECT cm.model_name, f.rating, f.comment, f.created_at
      FROM feedback f
      LEFT JOIN chat_messages cm ON cm.id = f.message_id
      ORDER BY f.created_at DESC
    `).all();
    const models = {};
    for (const row of rows) {
      const key = row.model_name || "unknown";
      if (!models[key]) models[key] = { modelName: key, thumbsUp: 0, thumbsDown: 0, totalRatings: 0, comments: [] };
      models[key].totalRatings++;
      if (row.rating === 1) models[key].thumbsUp++;
      else models[key].thumbsDown++;
      if (row.comment && models[key].comments.length < 10) models[key].comments.push(row.comment);
    }
    res.json({ ok: true, feedback: Object.values(models) });
  } finally {
    db.close();
  }
});

// ─── API Provider admin routes ────────────────────────────────────────────────
// [GET] /api/admin/providers — Auth: requireAdmin — Purpose: list all external AI providers with model counts
app.get("/api/admin/providers", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const providers = db.prepare("SELECT id, name, type, base_url, enabled, created_at, updated_at FROM api_providers ORDER BY name").all();
    const result = providers.map(p => ({
      ...p,
      enabled: Boolean(p.enabled),
      modelCount: db.prepare("SELECT COUNT(*) AS c FROM api_models WHERE provider_id = ?").get(p.id).c,
    }));
    res.json({ ok: true, providers: result });
  } finally {
    db.close();
  }
});

// [POST] /api/admin/providers — Auth: requireAdmin — Purpose: add a new external provider (API key is AES-encrypted at rest)
app.post("/api/admin/providers", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const { name, type, base_url, api_key } = req.body;
    if (!name || !type || !api_key) return res.status(400).json({ error: "name, type, and api_key are required" });
    const id = uid("prov");
    const now = new Date().toISOString();
    db.prepare("INSERT INTO api_providers (id, name, type, base_url, api_key_enc, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)").run(
      id, name, type, base_url || null, encryptKey(api_key), now, now
    );
    appendAudit(req.user.name, "admin.provider.create", `Created provider ${name}`);
    res.json({ ok: true, provider: { id, name, type, base_url, enabled: true } });
  } catch (err) {
    res.status(400).json({ error: err.message });
  } finally {
    db.close();
  }
});

// [PUT] /api/admin/providers/:id — Auth: requireAdmin — Purpose: update provider (name, URL, key, enabled state)
app.put("/api/admin/providers/:id", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const { name, type, base_url, api_key, enabled } = req.body;
    const now = new Date().toISOString();
    const existing = db.prepare("SELECT * FROM api_providers WHERE id = ?").get(req.params.id);
    if (!existing) return res.status(404).json({ error: "Provider not found" });
    const newName = name || existing.name;
    const newType = type || existing.type;
    const newBaseUrl = typeof base_url !== "undefined" ? base_url : existing.base_url;
    const newKeyEnc = api_key ? encryptKey(api_key) : existing.api_key_enc;
    const newEnabled = typeof enabled !== "undefined" ? (enabled ? 1 : 0) : existing.enabled;
    db.prepare("UPDATE api_providers SET name=?, type=?, base_url=?, api_key_enc=?, enabled=?, updated_at=? WHERE id=?").run(newName, newType, newBaseUrl, newKeyEnc, newEnabled, now, req.params.id);
    appendAudit(req.user.name, "admin.provider.update", `Updated provider ${req.params.id}`);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [DELETE] /api/admin/providers/:id — Auth: requireAdmin — Purpose: delete provider and all its synced api_models rows
app.delete("/api/admin/providers/:id", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const existing = db.prepare("SELECT id, name FROM api_providers WHERE id = ?").get(req.params.id);
    if (!existing) return res.status(404).json({ error: "Provider not found" });
    db.prepare("DELETE FROM api_models WHERE provider_id = ?").run(req.params.id);
    db.prepare("DELETE FROM api_providers WHERE id = ?").run(req.params.id);
    appendAudit(req.user.name, "admin.provider.delete", `Deleted provider ${existing.name}`);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// [POST] /api/admin/providers/:id/test — Auth: requireAdmin — Purpose: send a trivial prompt to verify provider connectivity
app.post("/api/admin/providers/:id/test", requireAdmin, async (req, res) => {
  const db = openSql();
  try {
    const provider = db.prepare("SELECT * FROM api_providers WHERE id = ?").get(req.params.id);
    if (!provider) return res.status(404).json({ error: "Provider not found" });
    // Pick the first synced model for this provider — never hardcode model names
    const firstModel = db.prepare(
      "SELECT model_id FROM api_models WHERE provider_id = ? ORDER BY is_approved DESC, display_name ASC LIMIT 1"
    ).get(provider.id);
    if (!firstModel) {
      return res.json({ ok: false, latency_ms: 0, error: "No models synced for this provider yet. Run Sync Models first." });
    }
    const start = Date.now();
    try {
      await callProvider(provider, firstModel.model_id, [{ role: "user", content: "Reply with one word: hello" }], { timeout: 15000 });
      res.json({ ok: true, latency_ms: Date.now() - start, modelTested: firstModel.model_id });
    } catch (err) {
      res.json({ ok: false, latency_ms: Date.now() - start, error: err.message, modelTested: firstModel.model_id });
    }
  } finally {
    db.close();
  }
});

/**
 * Keeps the main models table in sync with the api_models approval state.
 *
 * When a model is approved (is_approved=1), an "available" row is upserted into
 * models so the Auto Router can score and select it.  When approval is removed,
 * the row is deleted from models so the router stops seeing it.
 *
 * The model ID format in models is `{providerId}:{modelId}` (e.g. "prov-abc:claude-3-5-sonnet").
 *
 * @param {DatabaseSync} db
 * @param {{ id: string }} provider - api_providers row.
 * @param {{ model_id: string, display_name: string, context_window: number, is_approved: number|boolean }} apiModel
 */
function mirrorApiModelToModels(db, provider, apiModel) {
  const modelId = `${provider.id}:${apiModel.model_id}`;
  if (apiModel.is_approved) {
    db.prepare(`INSERT INTO models (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at)
      VALUES (?, ?, ?, ?, 'available', '["general","ask"]', 70, 80, 'external', ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        name = excluded.name, status = 'available',
        context_size = COALESCE(excluded.context_size, context_size),
        last_seen_at = excluded.last_seen_at`
    ).run(modelId, apiModel.display_name || apiModel.model_id, provider.id, apiModel.model_id, apiModel.context_window || null, new Date().toISOString());
  } else {
    // Remove from main models table — model is no longer approved
    db.prepare("DELETE FROM models WHERE id = ?").run(modelId);
  }
}

// [POST] /api/admin/providers/:id/sync — Auth: requireAdmin — Purpose: fetch model list from provider API and upsert into api_models
app.post("/api/admin/providers/:id/sync", requireAdmin, async (req, res) => {
  const db = openSql();
  try {
    const provider = db.prepare("SELECT * FROM api_providers WHERE id = ?").get(req.params.id);
    if (!provider) return res.status(404).json({ error: "Provider not found" });
    let models = [];
    const apiKey = decryptKey(provider.api_key_enc);
    if (provider.type === "anthropic") {
      // Call the real Anthropic models API — no hardcoded lists
      const base = cleanBaseUrl(provider.base_url || "https://api.anthropic.com");
      const response = await fetch(`${base}/v1/models`, {
        headers: { "x-api-key": apiKey, "anthropic-version": "2023-06-01" },
        signal: AbortSignal.timeout(15000),
      });
      if (response.ok) {
        const data = await response.json();
        models = (data.data || []).map(m => ({
          id: m.id,
          name: m.display_name || m.id,
          context: null, // Anthropic API does not return context_window in list endpoint
        }));
      } else {
        throw new Error(`Anthropic API returned ${response.status}: ${await response.text().catch(() => "")}`);
      }
    } else {
      const base = cleanBaseUrl(provider.base_url || "https://api.openai.com/v1");
      const url = provider.type === "groq" ? `${base}/v1/models` : `${base}/models`;
      const response = await fetch(url, {
        headers: { "Authorization": `Bearer ${apiKey}` },
        signal: AbortSignal.timeout(15000),
      });
      if (response.ok) {
        const data = await response.json();
        models = (data.data || data.models || []).map(m => ({ id: m.id || m.name, name: m.id || m.name, context: m.context_window || null }));
      } else {
        throw new Error(`Provider API returned ${response.status}`);
      }
    }
    const now = new Date().toISOString();
    for (const m of models) {
      const rowId = `${provider.id}:${m.id}`;
      const existing = db.prepare("SELECT id, is_approved FROM api_models WHERE id = ?").get(rowId);
      if (!existing) {
        db.prepare("INSERT INTO api_models (id, provider_id, model_id, display_name, context_window, is_approved, governance_tag, created_at) VALUES (?, ?, ?, ?, ?, 0, 'approved', ?)").run(rowId, provider.id, m.id, m.name, m.context || null, now);
      }
      // Mirror already-approved models into the main models table so the router can see them
      if (existing?.is_approved) {
        mirrorApiModelToModels(db, provider, { ...m, id: rowId, model_id: m.id, display_name: m.name, context_window: m.context, is_approved: 1 });
      }
    }
    appendAudit(req.user.name, "admin.provider.sync", `Synced models for provider ${provider.name}`);
    res.json({ ok: true, synced: models.length });
  } catch (err) {
    res.status(500).json({ error: err.message });
  } finally {
    db.close();
  }
});

// [GET] /api/admin/providers/:id/models — Auth: requireAdmin — Purpose: list all api_models for a provider with approval status
app.get("/api/admin/providers/:id/models", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const models = db.prepare("SELECT * FROM api_models WHERE provider_id = ? ORDER BY display_name").all(req.params.id);
    res.json({ ok: true, models: models.map(m => ({ ...m, isApproved: Boolean(m.is_approved) })) });
  } finally {
    db.close();
  }
});

// [PUT] /api/admin/providers/:id/models/:modelId — Auth: requireAdmin — Purpose: approve/restrict a model and mirror to main models table
app.put("/api/admin/providers/:id/models/:modelId", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const rowId = `${req.params.id}:${req.params.modelId}`;
    const existing = db.prepare("SELECT * FROM api_models WHERE id = ?").get(rowId);
    if (!existing) return res.status(404).json({ error: "Model not found" });
    const { governance_tag, is_approved, display_name } = req.body;
    if (typeof governance_tag !== "undefined") db.prepare("UPDATE api_models SET governance_tag = ? WHERE id = ?").run(governance_tag, rowId);
    if (typeof is_approved !== "undefined") db.prepare("UPDATE api_models SET is_approved = ? WHERE id = ?").run(is_approved ? 1 : 0, rowId);
    if (typeof display_name !== "undefined") db.prepare("UPDATE api_models SET display_name = ? WHERE id = ?").run(display_name, rowId);
    // When approval status changes, mirror into/out of the main models table so the router sees it
    if (typeof is_approved !== "undefined") {
      const provider = db.prepare("SELECT * FROM api_providers WHERE id = ?").get(req.params.id);
      if (provider) {
        const updated = db.prepare("SELECT * FROM api_models WHERE id = ?").get(rowId);
        mirrorApiModelToModels(db, provider, updated);
      }
    }
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

// ─── Migration ─────────────────────────────────────────────────────────────────

/**
 * One-time migration: reads documents.json (the legacy flat-file store) and
 * imports all chats, chat history, audit events, router traces, and workspace
 * prefs into SQLite.
 *
 * The migration is idempotent: if documents.json.migrated already exists, it
 * returns immediately.  After a successful import, documents.json is renamed
 * to documents.json.migrated so the function never runs again.
 *
 * Individual record failures are silently swallowed so a single malformed
 * message cannot abort the entire migration.
 *
 * @param {DatabaseSync} db - Open database handle.
 */
function migrateDocumentsJson(db) {
  if (!fs.existsSync(DOC_PATH)) return;
  const migratedPath = DOC_PATH + ".migrated";
  if (fs.existsSync(migratedPath)) { console.log("[migration] documents.json already migrated, skipping."); return; }
  console.log("[migration] Migrating documents.json to SQLite…");
  let docs;
  try { docs = JSON.parse(fs.readFileSync(DOC_PATH, "utf8")); } catch { console.log("[migration] Could not parse documents.json, skipping."); return; }

  const now = new Date().toISOString();
  let sessionCount = 0, msgCount = 0, auditCount = 0, traceCount = 0, prefCount = 0;

  // Migrate chats
  for (const [userId, chat] of Object.entries(docs.chats || {})) {
    try {
      const sessionId = chat.id || uid("chat");
      const existing = db.prepare("SELECT id FROM chat_sessions WHERE id = ?").get(sessionId);
      if (!existing) {
        db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)").run(
          sessionId, userId, chat.title || "New Chat", chat.pinned ? 1 : 0, chat.archived ? 1 : 0, chat.unread ? 1 : 0, chat.createdAt || now, chat.updatedAt || now
        );
        sessionCount++;
        for (const msg of (chat.messages || [])) {
          db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
            uid("msg"), sessionId, msg.role, msg.content, msg.mode || null, msg.modelId || null, msg.modelName || null, msg.routeReason || null, msg.live !== false ? 1 : 0, JSON.stringify(msg.artifacts || []), JSON.stringify(msg.extractedFiles || []), msg.createdAt || now
          );
          msgCount++;
        }
      }
    } catch {}
  }

  // Migrate chat history
  for (const [userId, threads] of Object.entries(docs.chatHistory || {})) {
    for (const chat of (threads || [])) {
      try {
        const sessionId = chat.id || uid("chat");
        const existing = db.prepare("SELECT id FROM chat_sessions WHERE id = ?").get(sessionId);
        if (!existing) {
          db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)").run(
            sessionId, userId, chat.title || "Archived Chat", chat.pinned ? 1 : 0, chat.archived ? 1 : 0, chat.unread ? 1 : 0, chat.createdAt || now, chat.updatedAt || now
          );
          sessionCount++;
          for (const msg of (chat.messages || [])) {
            db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
              uid("msg"), sessionId, msg.role, msg.content, msg.mode || null, msg.modelId || null, msg.modelName || null, msg.routeReason || null, msg.live !== false ? 1 : 0, JSON.stringify(msg.artifacts || []), JSON.stringify(msg.extractedFiles || []), msg.createdAt || now
            );
            msgCount++;
          }
        }
      } catch {}
    }
  }

  // Migrate audit
  for (const item of (docs.audit || [])) {
    try {
      const existing = db.prepare("SELECT id FROM audit_events WHERE id = ?").get(item.id);
      if (!existing) {
        db.prepare("INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(
          item.id || uid("audit"), item.actor || "", item.action || "", item.detail || "", JSON.stringify(item.extra || {}), item.createdAt || now
        );
        auditCount++;
      }
    } catch {}
  }

  // Migrate router traces
  for (const trace of (docs.routerTraces || [])) {
    try {
      const existing = db.prepare("SELECT id FROM router_traces WHERE id = ?").get(trace.id);
      if (!existing) {
        db.prepare("INSERT INTO router_traces (id, user_id, message, mode, selected_model_id, tags_json, candidates_json, live, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
          trace.id || uid("trace"), trace.userId || null, trace.message || "", trace.mode || "", trace.selectedModelId || null, JSON.stringify(trace.tags || []), JSON.stringify(trace.candidates || []), trace.live ? 1 : 0, trace.createdAt || now
        );
        traceCount++;
      }
    } catch {}
  }

  // Migrate workspace prefs
  for (const [userId, prefs] of Object.entries(docs.workspacePrefs || {})) {
    try {
      const existing = db.prepare("SELECT user_id FROM workspace_prefs WHERE user_id = ?").get(userId);
      if (!existing && prefs.workspaceRoot) {
        db.prepare("INSERT INTO workspace_prefs (user_id, workspace_root, permission_mode, updated_at) VALUES (?, ?, ?, ?)").run(
          userId, prefs.workspaceRoot, prefs.permissionMode || "default", prefs.updatedAt || now
        );
        prefCount++;
      }
    } catch {}
  }

  console.log(`[migration] Done: ${sessionCount} sessions, ${msgCount} messages, ${auditCount} audit events, ${traceCount} traces, ${prefCount} workspace prefs.`);
  try { fs.renameSync(DOC_PATH, migratedPath); console.log(`[migration] Renamed documents.json → documents.json.migrated`); } catch {}
}

// [GET] / — Auth: public — Purpose: redirect root to /login
app.get("/", (req, res) => res.redirect("/login"));

// [GET] /login — Auth: public — Purpose: serve login.html; redirects to /app if already authenticated
app.get("/login", (req, res) => {
  if (sessionUser(req)) return res.redirect("/app");
  res.sendFile(path.join(STATIC_DIR, "login.html"));
});

// [GET] /app — Auth: requireAuth (redirect) — Purpose: serve app.html; redirects to /login if unauthenticated
app.get("/app", (req, res) => {
  if (!sessionUser(req)) return res.redirect("/login");
  res.sendFile(path.join(STATIC_DIR, "app.html"));
});

// [GET] /admin-login — Auth: public — Purpose: serve admin-login.html; redirects admins to /admin, other users to /app
app.get("/admin-login", (req, res) => {
  const user = sessionUser(req);
  if (user && user.role === "admin") return res.redirect("/admin");
  if (user) return res.redirect("/app");
  res.sendFile(path.join(STATIC_DIR, "admin-login.html"));
});

// [GET] /admin — Auth: admin session (redirect) — Purpose: serve admin.html; non-admins redirected to /app
app.get("/admin", (req, res) => {
  const user = sessionUser(req);
  if (!user) return res.redirect("/admin-login");
  if (user.role !== "admin") return res.redirect("/app");
  res.sendFile(path.join(STATIC_DIR, "admin.html"));
});

app.use((req, res) => {
  res.redirect("/login");
});

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
const server = http.createServer(app);

const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  if (req.url !== "/ws/terminal") { socket.destroy(); return; }

  // Auth: parse session cookie
  const cookies = Object.fromEntries(
    String(req.headers.cookie || "").split(";")
      .map(s => s.trim()).filter(Boolean)
      .map(s => { const i = s.indexOf("="); return [s.slice(0, i), decodeURIComponent(s.slice(i + 1))]; })
  );
  const session = sessions.get(cookies.olla_nest_session);
  if (!session || session.expiresAt < Date.now()) { socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n"); socket.destroy(); return; }

  const user = session.user;
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
  const term = pty.spawn(shell, [], {
    name: "xterm-256color",
    cols: 120,
    rows: 30,
    cwd,
    env: {
      ...process.env,
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

server.listen(PORT, async () => {
  // Initial startup tasks
  const db = openSql();
  try {
    migrateDocumentsJson(db);
  } finally {
    db.close();
  }

  // Initial Ollama sync then background refresh every 30 seconds.
  // Each tick opens and closes its own DB connection — completely independent
  // of any HTTP request lifecycle, no race conditions possible.
  async function runOllamaSync() {
    const syncDb = openSql();
    try {
      await syncOllamaModels(syncDb);
    } catch (_) {
      // unreachable — syncOllamaModels catches internally
    } finally {
      syncDb.close();
    }
  }
  runOllamaSync(); // run immediately on boot
  setInterval(runOllamaSync, 30000); // then every 30 seconds

  console.log(`Olla Nest running at http://localhost:${PORT}`);
});
