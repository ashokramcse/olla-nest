/**
 * @file src/db/index.js
 * @description SQLite database connection helpers: openSql, ensureDataDir, rows, one.
 */

const fs = require("fs");
const { DatabaseSync } = require("node:sqlite");
const { DATA_DIR, SQL_PATH } = require("../config");
const { seedSql } = require("./schema");

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
/**
 * Opens (or creates) the SQLite database and returns a handle.
 * Schema initialisation is done once at startup via initDatabase() — not here.
 *
 * @returns {DatabaseSync} An open SQLite database handle.  Caller MUST call
 *   db.close() in a finally block to release the file lock.
 */
function openSql() {
  ensureDataDir();
  const db = new DatabaseSync(SQL_PATH);
  // DELETE journal mode (the SQLite default): each commit is written directly
  // to the main database file.  WAL mode produces a 0-byte wal file on some
  // Docker/macOS virtualised filesystems, making cross-connection reads
  // invisible.  synchronous=FULL ensures durability on every commit.
  db.exec("PRAGMA journal_mode=DELETE; PRAGMA foreign_keys=ON; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;");
  return db;
}

/**
 * One-time startup function: creates all tables, adds indexes, and seeds default
 * data.  Must be called once before server.listen() — NOT on every request open.
 */
function initDatabase() {
  ensureDataDir();
  const db = new DatabaseSync(SQL_PATH);
  try {
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
      user_id TEXT,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      mode TEXT,
      model_id TEXT,
      model_name TEXT,
      route_reason TEXT,
      tokens_used INTEGER DEFAULT 0,
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
    -- sessions: persistent login sessions replacing the in-memory Map
    CREATE TABLE IF NOT EXISTS sessions (
      token TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
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
  // DELETE journal mode (the SQLite default): each commit is written directly
  // to the main database file.  WAL mode produces a 0-byte wal file on some
  // Docker/macOS virtualised filesystems, making cross-connection reads
  // invisible.  synchronous=FULL ensures durability on every commit.
  db.exec("PRAGMA journal_mode=DELETE; PRAGMA foreign_keys=ON; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;");
  // Schema version table — tracks applied migrations so they run exactly once
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version INTEGER PRIMARY KEY,
      applied_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
  `);
  // Runtime migrations — numbered, run exactly once via schema_migrations guard
  const MIGRATIONS = [
    // v1 — add missing columns to chat_messages (deployed 2026-05-21)
    [1, "ALTER TABLE chat_messages ADD COLUMN user_id TEXT"],
    [2, "ALTER TABLE chat_messages ADD COLUMN tokens_used INTEGER DEFAULT 0"],
    [3, "ALTER TABLE chat_messages ADD COLUMN latency_ms INTEGER"],
  ];
  const applied = new Set(
    db.prepare("SELECT version FROM schema_migrations").all().map(r => r.version)
  );
  for (const [ver, sql] of MIGRATIONS) {
    if (applied.has(ver)) continue;
    try {
      db.exec(sql);
    } catch (_) { /* column may already exist on a fresh DB — safe to skip */ }
    db.prepare("INSERT OR IGNORE INTO schema_migrations (version) VALUES (?)").run(ver);
  }
  // Performance indexes
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
    CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
    CREATE INDEX IF NOT EXISTS idx_chat_messages_user_date ON chat_messages(user_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_messages_created_at ON chat_messages(created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active ON chat_sessions(user_id, is_active);
    CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at);
    CREATE INDEX IF NOT EXISTS idx_router_traces_created_at ON router_traces(created_at);
    CREATE INDEX IF NOT EXISTS idx_feedback_message_id ON feedback(message_id);
    CREATE INDEX IF NOT EXISTS idx_access_grants_subject ON access_grants(subject_type, subject_id);
    CREATE INDEX IF NOT EXISTS idx_user_overrides_user ON user_overrides(user_id);
  `);
  // Login rate-limiting table — shared across all cluster workers via SQLite
  db.exec(`
    CREATE TABLE IF NOT EXISTS login_attempts (
      ip TEXT PRIMARY KEY,
      count INTEGER NOT NULL DEFAULT 0,
      reset_at INTEGER NOT NULL
    );
  `);
  seedSql(db);
  } finally {
    db.close();
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

module.exports = { openSql, initDatabase, ensureDataDir, rows, one };
