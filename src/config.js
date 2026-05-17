/**
 * @file src/config.js
 * @description All constants and environment variable bindings for Olla Nest.
 */

const path = require("path");
const crypto = require("crypto");

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
const DATA_DIR = path.join(__dirname, "..", "data");

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
 * SQLite.  Once migrated it is renamed to documents.json.migrated and this
 * function effectively becomes a no-op.
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
const STATIC_DIR = path.join(__dirname, "..", "public");

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

/** Maximum failed login attempts before a 15-minute lockout is triggered. */
const LOGIN_MAX_ATTEMPTS = 10;
const LOGIN_WINDOW_MS = 15 * 60 * 1000; // 15 minutes

module.exports = {
  PORT,
  OLLAMA_URL,
  DATA_DIR,
  DEFAULT_WORKSPACE_ROOT,
  SQL_PATH,
  DOC_PATH,
  DEFAULT_ADMIN_EMAIL,
  DEFAULT_ADMIN_PASSWORD,
  DEFAULT_USER_PASSWORD,
  STATIC_DIR,
  STORAGE_MODE,
  SECRET_KEY,
  SESSION_SECRET,
  LOGIN_MAX_ATTEMPTS,
  LOGIN_WINDOW_MS,
};
