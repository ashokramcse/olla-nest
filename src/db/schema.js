/**
 * @file src/db/schema.js
 * @description Database schema: initDb (all CREATE TABLE), seedSql, ensureColumn, migrateDocumentsJson.
 */

const fs = require("fs");
const bcrypt = require("bcryptjs");
const {
  DEFAULT_ADMIN_EMAIL,
  DEFAULT_ADMIN_PASSWORD,
  DEFAULT_USER_PASSWORD,
  OLLAMA_URL,
  DEFAULT_WORKSPACE_ROOT,
  STORAGE_MODE,
  DOC_PATH,
} = require("../config");

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
 * Reads a single platform setting from the settings table.
 * Returns `fallback` if the key does not exist.
 * Automatically coerces the stored string "true"/"false" to booleans.
 *
 * @param {DatabaseSync} db - Open database handle.
 * @param {string} key - Settings key (e.g. "routerEnabled").
 * @param {*} fallback - Value returned when the key is absent.
 * @returns {string|boolean} The stored value or fallback.
 */
function _setting(db, key, fallback) {
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
function _setSetting(db, key, value) {
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").run(key, String(value));
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
    ].forEach(([key, value]) => _setSetting(db, key, value));
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
  const { uid } = require("../models/model");
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

module.exports = { seedSql, ensureColumn, tableCount, migrateDocumentsJson };
