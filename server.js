const express = require("express");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const bcrypt = require("bcryptjs");
const { DatabaseSync } = require("node:sqlite");

const app = express();
const PORT = process.env.PORT || 3000;
const OLLAMA_URL = process.env.OLLAMA_URL || "http://host.docker.internal:11434";
const DATA_DIR = path.join(__dirname, "data");
const DEFAULT_WORKSPACE_ROOT = process.env.WORKSPACE_ROOT || path.join(DATA_DIR, "workspace");
const SQL_PATH = process.env.SQLITE_PATH || path.join(DATA_DIR, "olla-nest.sqlite");
const DOC_PATH = process.env.DOCUMENT_DB_PATH || path.join(DATA_DIR, "documents.json");
const DEFAULT_ADMIN_EMAIL = process.env.DEFAULT_ADMIN_EMAIL || "admin@ollanest.local";
const DEFAULT_ADMIN_PASSWORD = process.env.DEFAULT_ADMIN_PASSWORD || "ChangeMe!CreateARealPassword123";
const DEFAULT_USER_PASSWORD = process.env.DEFAULT_USER_PASSWORD || "UserDemo!12345";
const STATIC_DIR = path.join(__dirname, "public");
const sessions = new Map();

function enforceDockerRuntime() {
  const inDocker = fs.existsSync("/.dockerenv") || process.env.OLLA_NEST_DOCKER_RUNTIME === "true";
  if (!inDocker && process.env.ALLOW_NON_DOCKER !== "1") {
    console.error("Olla Nest is Docker-only. Start it with: docker compose up --build");
    console.error("For one-off diagnostics only, set ALLOW_NON_DOCKER=1.");
    process.exit(1);
  }
}

enforceDockerRuntime();

app.use(express.json({ limit: "2mb" }));
app.use(express.static(STATIC_DIR));

function uid(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
}

function openSql() {
  ensureDataDir();
  const db = new DatabaseSync(SQL_PATH);
  db.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS departments (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS groups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL
    );
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
    CREATE TABLE IF NOT EXISTS user_groups (
      user_id TEXT NOT NULL,
      group_id TEXT NOT NULL,
      PRIMARY KEY (user_id, group_id)
    );
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
    CREATE TABLE IF NOT EXISTS access_grants (
      id TEXT PRIMARY KEY,
      subject_type TEXT NOT NULL,
      subject_id TEXT NOT NULL,
      model_id TEXT NOT NULL,
      can_use INTEGER NOT NULL DEFAULT 1
    );
    CREATE TABLE IF NOT EXISTS role_catalog (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      description TEXT,
      permissions TEXT NOT NULL DEFAULT '[]',
      system_role INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS permission_catalog (
      key TEXT PRIMARY KEY,
      category TEXT NOT NULL,
      description TEXT NOT NULL,
      risk_level TEXT NOT NULL DEFAULT 'low'
    );
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
  `);
  seedSql(db);
  return db;
}

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

function writeDocs(docs) {
  fs.writeFileSync(DOC_PATH, JSON.stringify(docs, null, 2));
}

function setting(db, key, fallback) {
  const row = db.prepare("SELECT value FROM settings WHERE key = ?").get(key);
  if (!row) return fallback;
  if (row.value === "true") return true;
  if (row.value === "false") return false;
  return row.value;
}

function setSetting(db, key, value) {
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").run(key, String(value));
}

function tableCount(db, table) {
  return db.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get().count;
}

function ensureColumn(db, table, column, definition) {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all().map((row) => row.name);
  if (!columns.includes(column)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
}

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
      ["workspace:build", "Local Work", "Create local workspace files", "medium"],
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
  const admin = db.prepare("SELECT id, email, password_hash FROM users WHERE id = 'u-admin'").get();
  if (admin && (!admin.email || !admin.password_hash)) {
    db.prepare("UPDATE users SET email = ?, password_hash = ? WHERE id = 'u-admin'").run(DEFAULT_ADMIN_EMAIL, bcrypt.hashSync(DEFAULT_ADMIN_PASSWORD, 12));
  }
}

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

function cleanBaseUrl(value) {
  return String(value || "").replace(/\/+$/, "");
}

function ollamaUrl(db) {
  return cleanBaseUrl(setting(db, "ollamaUrl", OLLAMA_URL));
}

async function fetchOllamaModels(url = OLLAMA_URL) {
  const baseUrl = cleanBaseUrl(url);
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), 5000);
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

async function syncOllamaModels(db) {
  const installed = await fetchOllamaModels(ollamaUrl(db));
  const seenIds = [];
  for (const item of installed) {
    const modelRef = item.name;
    const { speedScore, qualityScore } = inferScores(modelRef, item.size);
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
  return installed;
}

function ensureDefaultAccess(db) {
  const models = db.prepare("SELECT id FROM models WHERE provider = 'ollama'").all();
  const grants = db.prepare("SELECT COUNT(*) AS count FROM access_grants").get().count;
  if (grants > 0 || models.length === 0) return;
  const insert = db.prepare("INSERT INTO access_grants (id, subject_type, subject_id, model_id, can_use) VALUES (?, ?, ?, ?, 1)");
  for (const model of models) {
    insert.run(uid("grant"), "group", "group-all", model.id);
  }
}

function rows(db, query, ...params) {
  return db.prepare(query).all(...params);
}

function one(db, query, ...params) {
  return db.prepare(query).get(...params);
}

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
    externalCostTier: row.external_cost_tier || "local-free",
    sensitiveAllowed: row.sensitive_allowed !== 0,
  };
}

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
  };
}

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
  access_status AS accessStatus, access_expires_at AS accessExpiresAt, last_active_at AS lastActiveAt`;

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

function routeModel(db, user, message, mode) {
  const candidates = allowedModels(db, user);
  const tags = classifyRequest(message, mode);
  if (!setting(db, "routerEnabled", true)) {
    return {
      selected: candidates[0],
      tags: ["router-disabled"],
      candidates: candidates.map((model) => ({ id: model.id, name: model.name, score: 0 })),
      reason: "Auto Router is disabled by admin, so the first approved model was selected.",
    };
  }

  const scored = candidates.map((model) => {
    const matches = model.capabilities.filter((capability) => tags.includes(capability));
    const capabilityScore = matches.length * 35;
    const specialistScore = ["medical", "ocr", "vision", "coding"].some((tag) => tags.includes(tag) && model.capabilities.includes(tag)) ? 45 : 0;
    const speedWeight = message.length < 240 ? 0.34 : 0.22;
    const qualityWeight = 0.44;
    const privacyScore = model.privacy === "local" ? 10 : setting(db, "allowApiModels", false) ? 0 : -100;
    const score = capabilityScore + specialistScore + model.speedScore * speedWeight + model.qualityScore * qualityWeight + privacyScore;
    return { model, score: Math.round(score), matches };
  });

  scored.sort((a, b) => b.score - a.score);
  const selected = scored[0]?.model;
  return {
    selected,
    tags,
    candidates: scored.map((item) => ({ id: item.model.id, name: item.model.name, score: item.score, matches: item.matches })),
    reason: selected
      ? `Selected ${selected.name} by matching request capabilities, speed, quality, privacy, and the user's approved access.`
      : "No approved active model is available for this user.",
  };
}

async function ollamaGenerate(db, model, prompt) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 300000); /* 5 min — large models need time to load */
  try {
    const response = await fetch(`${ollamaUrl(db)}/api/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: controller.signal,
      body: JSON.stringify({
        model,
        prompt,
        stream: false,
        think: false,
        options: { temperature: 0.5, num_predict: 4096 },
      }),
    });
    if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
    const data = await response.json();
    return cleanModelOutput(data.response || data.message?.content || data.output || "");
  } finally {
    clearTimeout(timeout);
  }
}

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

function modelPrompt(message, mode, route, workspace) {
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
  return `${base.join("\n")}\nMode: ${mode}\nInstruction: ${modeInstructions[mode] || modeInstructions.ask}\n\nUser request:\n${message.trim()}`;
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
  const docs = readDocs();
  const prefs = docs.workspacePrefs?.[userId] || {};
  const root = path.resolve(String(prefs.workspaceRoot || workspaceRoot(db)));
  return {
    workspaceRoot: root,
    outputFolder: path.join(root, "olla-nest-output"),
    permissionMode: normalizePermissionMode(prefs.permissionMode || setting(db, "localPermissionMode", "default")),
    localWritesEnabled: setting(db, "localWritesEnabled", true),
  };
}

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

function appendAudit(actor, action, detail, extra = {}) {
  const docs = readDocs();
  docs.audit.push({ id: uid("audit"), actor, action, detail, extra, createdAt: new Date().toISOString() });
  docs.audit = docs.audit.slice(-200);
  writeDocs(docs);
}

function appendTrace(trace) {
  const docs = readDocs();
  docs.routerTraces.push({ id: uid("trace"), ...trace, createdAt: new Date().toISOString() });
  docs.routerTraces = docs.routerTraces.slice(-200);
  writeDocs(docs);
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
  };
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

function requireAuth(req, res, next) {
  const user = sessionUser(req);
  if (!user) return res.status(401).json({ error: "Login required" });
  req.user = user;
  next();
}

function requireAdmin(req, res, next) {
  const user = sessionUser(req);
  if (!user) return res.status(401).json({ error: "Login required" });
  if (user.role !== "admin") return res.status(403).json({ error: "Admin access required" });
  req.user = user;
  next();
}

function hasRight(user, right) {
  return user.role === "admin" || (user.rights || []).includes(right);
}

function setSession(res, user) {
  const token = crypto.randomBytes(32).toString("hex");
  sessions.set(token, { user, expiresAt: Date.now() + 1000 * 60 * 60 * 12 });
  res.setHeader("Set-Cookie", `olla_nest_session=${encodeURIComponent(token)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200`);
}

app.post("/api/auth/login", (req, res) => {
  const db = openSql();
  try {
    const { email, password } = req.body;
    const row = one(db, `SELECT ${USER_SELECT}, password_hash FROM users WHERE email = ? AND active = 1`, email);
    if (!row || !row.password_hash || !bcrypt.compareSync(String(password || ""), row.password_hash)) {
      return res.status(401).json({ error: "Invalid email or password" });
    }
    const user = publicUser(row);
    setSetting(db, "activeUserId", user.id);
    setSession(res, user);
    appendAudit(user.name, "auth.login", "User signed in");
    res.json({ ok: true, user, redirectTo: user.role === "admin" ? "/admin" : "/app" });
  } finally {
    db.close();
  }
});

app.post("/api/auth/logout", (req, res) => {
  const token = parseCookies(req).olla_nest_session;
  if (token) sessions.delete(token);
  res.setHeader("Set-Cookie", "olla_nest_session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
  res.json({ ok: true });
});

app.get("/api/auth/me", (req, res) => {
  const user = sessionUser(req);
  res.json({ authenticated: Boolean(user), user });
});

app.get("/api/bootstrap", (req, res) => {
  res.json({
    adminEmail: DEFAULT_ADMIN_EMAIL,
    defaultPasswordHint: DEFAULT_ADMIN_PASSWORD === "ChangeMe!CreateARealPassword123" ? "ChangeMe!CreateARealPassword123" : "Configured by DEFAULT_ADMIN_PASSWORD",
  });
});

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

app.get("/api/state", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    setSetting(db, "activeUserId", req.user.id);
    await syncOllamaModels(db).catch(() => []);
    const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
    const docs = readDocs();
    const models = rows(db, "SELECT * FROM models ORDER BY provider, name").map(parseModel);
    res.json({
      activeUser: user,
      users: getUsers(db),
      departments: rows(db, "SELECT id, name FROM departments ORDER BY name"),
      groups: rows(db, "SELECT id, name FROM groups ORDER BY name"),
      models,
      settings: settingsState(db),
      chats: user.role === "admin" ? Object.values(docs.chats) : [chatFor(user.id)],
      audit: docs.audit.slice(-30).reverse(),
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

app.get("/api/ollama/models", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    const installed = await syncOllamaModels(db);
    res.json({ ok: true, models: installed });
  } catch (error) {
    res.json({ ok: false, error: error.message, models: [] });
  } finally {
    db.close();
  }
});

const MAC_HOME = "/mac-home"; /* bind-mounted from ${HOME} on the host */

/* Browse local filesystem directories */
app.get("/api/workspace/browse", requireAuth, (req, res) => {
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

app.post("/api/workspace/local-settings", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const workspaceRootInput = String(req.body.workspaceRoot || "").trim();
    const permissionMode = normalizePermissionMode(req.body.permissionMode);
    const docs = readDocs();
    if (!workspaceRootInput) {
      /* clear user's custom workspace — revert to global default */
      delete docs.workspacePrefs[req.user.id];
      writeDocs(docs);
      appendAudit(req.user.name, "workspace.local.clear", "Cleared local workspace folder");
      return res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
    }
    const nextRoot = path.resolve(workspaceRootInput);
    fs.mkdirSync(nextRoot, { recursive: true });
    docs.workspacePrefs[req.user.id] = {
      workspaceRoot: nextRoot,
      permissionMode,
      updatedAt: new Date().toISOString(),
    };
    writeDocs(docs);
    appendAudit(req.user.name, "workspace.local.save", `Updated local workspace folder to ${nextRoot}`, { permissionMode });
    res.json({ ok: true, workspace: workspaceForUser(db, req.user.id) });
  } finally {
    db.close();
  }
});

app.post("/api/chat", requireAuth, async (req, res) => {
  const db = openSql();
  try {
    const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
    if (!hasRight(user, "chat:use")) return res.status(403).json({ error: "Chat access is not enabled for this account" });
    const { message, mode = "ask", manualModelId } = req.body;
    if (!message || !message.trim()) return res.status(400).json({ error: "Message is required" });
    await syncOllamaModels(db).catch(() => []);

    const manualModel = manualModelId ? allowedModels(db, user).find((model) => model.id === manualModelId) : null;
    const route = manualModel
      ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model." }
      : routeModel(db, user, message, mode);

    if (!route.selected) return res.status(403).json({ error: route.reason });

    const workspace = workspaceForUser(db, user.id);
    let content;
    let live = true;
    try {
      if (route.selected.provider !== "ollama") throw new Error("API connector is not configured in this MVP.");
      content = await ollamaGenerate(db, route.selected.model, modelPrompt(message, mode, route, workspace));
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

    const docs = readDocs();
    const chat = chatFor(user.id);
    const now = new Date().toISOString();
    chat.messages.push({ role: "user", content: message, mode, createdAt: now });
    chat.messages.push({
      role: "assistant",
      content: chatContent,
      modelId: route.selected.id,
      modelName: route.selected.name,
      routeReason: route.reason,
      live,
      artifacts,
      extractedFiles,
      createdAt: now,
    });
    /* Auto-set title from first user message if still default */
    if (!chat.title || chat.title === "New Chat" || chat.title === "New workspace") {
      chat.title = autoTitle(chat.messages);
    }
    chat.updatedAt = now;
    docs.chats[user.id] = chat;
    writeDocs(docs);
    appendTrace({ userId: user.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live, artifacts, workspace });
    appendAudit(user.name, "chat.request", `${mode.toUpperCase()} routed to ${route.selected.name}`, { live, artifacts });
    res.json({ content, route, model: route.selected, live, artifacts, extractedFiles, chat });
  } finally {
    db.close();
  }
});

function autoTitle(messages) {
  const first = messages.find(m => m.role === "user");
  if (!first) return "New chat";
  return String(first.content).slice(0, 45).trim() + (first.content.length > 45 ? "…" : "");
}

function archiveCurrentChat(userId) {
  const docs = readDocs();
  const chat = docs.chats[userId];
  if (!chat) return;
  const hasUserMsg = (chat.messages || []).some(m => m.role === "user");
  if (!hasUserMsg) return;
  if (!docs.chatHistory[userId]) docs.chatHistory[userId] = [];
  const thread = {
    ...chat,
    title: chat.title && chat.title !== "New workspace" ? chat.title : autoTitle(chat.messages),
    pinned: false,
    archived: false,
    unread: false,
    updatedAt: new Date().toISOString(),
  };
  docs.chatHistory[userId].unshift(thread);
  docs.chatHistory[userId] = docs.chatHistory[userId].slice(0, 100);
  writeDocs(docs);
}

app.delete("/api/chat", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const docs = readDocs();
    delete docs.chats[req.user.id];
    writeDocs(docs);
    chatFor(req.user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.post("/api/chat/clear", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    archiveCurrentChat(user.id);
    const docs = readDocs();
    delete docs.chats[user.id];
    writeDocs(docs);
    chatFor(user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.get("/api/threads", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const docs = readDocs();
    const active = docs.chats[user.id] || null;
    const history = (docs.chatHistory[user.id] || []).slice().sort((a, b) => {
      if (a.pinned !== b.pinned) return b.pinned ? 1 : -1;
      return new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0);
    });
    res.json({ active, history });
  } finally {
    db.close();
  }
});

app.delete("/api/threads/:id", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const docs = readDocs();
    docs.chatHistory[user.id] = (docs.chatHistory[user.id] || []).filter(t => t.id !== req.params.id);
    writeDocs(docs);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.patch("/api/threads/:id", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const docs = readDocs();
    const allowed = ["title", "pinned", "archived", "unread"];
    // Check active chat first
    if (docs.chats[user.id] && docs.chats[user.id].id === req.params.id) {
      allowed.forEach(k => { if (req.body[k] !== undefined) docs.chats[user.id][k] = req.body[k]; });
      docs.chats[user.id].updatedAt = new Date().toISOString();
      writeDocs(docs);
      return res.json({ ok: true, thread: docs.chats[user.id] });
    }
    // Fall back to history
    const threads = docs.chatHistory[user.id] || [];
    const idx = threads.findIndex(t => t.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: "Thread not found" });
    allowed.forEach(k => { if (req.body[k] !== undefined) threads[idx][k] = req.body[k]; });
    threads[idx].updatedAt = new Date().toISOString();
    docs.chatHistory[user.id] = threads;
    writeDocs(docs);
    res.json({ ok: true, thread: threads[idx] });
  } finally {
    db.close();
  }
});

app.post("/api/threads/:id/activate", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    archiveCurrentChat(user.id);
    const docs = readDocs();
    const threads = docs.chatHistory[user.id] || [];
    const idx = threads.findIndex(t => t.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: "Thread not found" });
    const thread = threads[idx];
    docs.chatHistory[user.id] = threads.filter((_, i) => i !== idx);
    docs.chats[user.id] = { ...thread, unread: false };
    writeDocs(docs);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.post("/api/threads/:id/fork", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    archiveCurrentChat(user.id);
    const docs = readDocs();
    const threads = docs.chatHistory[user.id] || [];
    const src = threads.find(t => t.id === req.params.id)
      || (docs.chats[user.id]?.id === req.params.id ? docs.chats[user.id] : null);
    if (!src) return res.status(404).json({ error: "Thread not found" });
    const forked = {
      ...src,
      id: uid("chat"),
      title: `Fork of ${src.title}`,
      pinned: false,
      archived: false,
      unread: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    docs.chats[user.id] = forked;
    writeDocs(docs);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

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

app.post("/api/admin/model-sources/test", requireAdmin, async (req, res) => {
  try {
    const testUrl = cleanBaseUrl(req.body.ollamaUrl || OLLAMA_URL);
    if (!/^https?:\/\/[^ "]+$/.test(testUrl)) return res.status(400).json({ error: "Ollama URL must start with http:// or https://" });
    const models = await fetchOllamaModels(testUrl);
    res.json({ ok: true, count: models.length, models });
  } catch (error) {
    res.json({ ok: false, error: error.message, count: 0, models: [] });
  }
});

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
    res.json({ ok: true, user: publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, id)) });
  } catch (error) {
    res.status(400).json({ error: error.message });
  } finally {
    db.close();
  }
});

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

app.get("/", (req, res) => res.redirect("/login"));

app.get("/login", (req, res) => {
  if (sessionUser(req)) return res.redirect("/app");
  res.sendFile(path.join(STATIC_DIR, "login.html"));
});

app.get("/app", (req, res) => {
  if (!sessionUser(req)) return res.redirect("/login");
  res.sendFile(path.join(STATIC_DIR, "app.html"));
});

app.get("/admin", (req, res) => {
  const user = sessionUser(req);
  if (!user) return res.redirect("/login");
  if (user.role !== "admin") return res.redirect("/app");
  res.sendFile(path.join(STATIC_DIR, "admin.html"));
});

app.use((req, res) => {
  res.redirect("/login");
});

app.listen(PORT, async () => {
  const db = openSql();
  try {
    await syncOllamaModels(db).catch(() => []);
  } finally {
    db.close();
  }
  console.log(`Olla Nest running at http://localhost:${PORT}`);
});
