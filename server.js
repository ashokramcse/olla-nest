const express = require("express");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const bcrypt = require("bcryptjs");
const { DatabaseSync } = require("node:sqlite");

const app = express();
const PORT = process.env.PORT || 3000;
const OLLAMA_URL = process.env.OLLAMA_URL || "http://localhost:11434";
const DATA_DIR = path.join(__dirname, "data");
const DEFAULT_WORKSPACE_ROOT = process.env.WORKSPACE_ROOT || path.join(DATA_DIR, "workspace");
const STORAGE_MODE = process.env.STORAGE_MODE || "local";
const DATABASE_URL = process.env.DATABASE_URL || "postgresql://olla_nest:olla_nest@localhost:5432/olla_nest";
const MONGODB_URI = process.env.MONGODB_URI || "mongodb://localhost:27017/olla_nest";
const REDIS_URL = process.env.REDIS_URL || "redis://localhost:6379";
const SQL_PATH = process.env.SQLITE_PATH || path.join(DATA_DIR, "olla-nest.sqlite");
const DOC_PATH = process.env.DOCUMENT_DB_PATH || path.join(DATA_DIR, "documents.json");
const DEFAULT_ADMIN_EMAIL = process.env.DEFAULT_ADMIN_EMAIL || "admin@ollanest.local";
const DEFAULT_ADMIN_PASSWORD = process.env.DEFAULT_ADMIN_PASSWORD || "ChangeMe!CreateARealPassword123";
const DEFAULT_USER_PASSWORD = process.env.DEFAULT_USER_PASSWORD || "UserDemo!12345";
const STATIC_DIR = fs.existsSync(path.join(__dirname, "dist")) ? path.join(__dirname, "dist") : path.join(__dirname, "public");
const sessions = new Map();

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
  for (const [key, fallback] of Object.entries({ chats: {}, audit: [], routerTraces: [], workspacePrefs: {} })) {
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

function seedSql(db) {
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
    `INSERT OR REPLACE INTO models
      (id, name, provider, model_ref, status, capabilities, speed_score, quality_score, privacy, context_size, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
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
  const response = await fetch(`${baseUrl}/api/tags`);
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
  };
}

function safeJson(value, fallback) {
  try {
    return JSON.parse(value || "");
  } catch {
    return fallback;
  }
}

function getUsers(db) {
  return rows(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users ORDER BY role, name").map(publicUser);
}

function activeUser(db) {
  const id = setting(db, "activeUserId", "u-admin");
  return publicUser(one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", id) || one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users LIMIT 1"));
}

function userGroupIds(db, userId) {
  return rows(db, "SELECT group_id AS id FROM user_groups WHERE user_id = ?", userId).map((row) => row.id);
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
  if (/(bug|error|fix|stack|trace|failing|broken|debug)/.test(text) || mode === "fix") ["fix", "debugging", "coding"].forEach((t) => tags.add(t));
  if (/(code|repo|component|api|server|frontend|backend|function|test|build)/.test(text) || mode === "build") ["coding", "build", "project"].forEach((t) => tags.add(t));
  if (/(review|risk|issue|security|quality|audit)/.test(text) || mode === "review") ["review", "analysis"].forEach((t) => tags.add(t));
  if (/(write|email|doc|document|pitch|copy|summarize|summary)/.test(text)) ["writing", "summary"].forEach((t) => tags.add(t));
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
  const timeout = setTimeout(() => controller.abort(), 45000);
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
        options: { temperature: 0.5, num_predict: 900 },
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

function modelPrompt(message, mode, route) {
  const base = [
    "You are Olla Nest, a company AI workspace assistant.",
    "Answer the user's request directly and completely.",
    "Do not answer with only the selected model name.",
    "Do not include hidden thinking, <think> blocks, or internal reasoning traces.",
    `Selected model route: ${route.selected?.name || "auto"}.`,
    `Routing reason: ${route.reason}`,
  ];
  const modeInstructions = {
    ask: "Give a clear, useful answer with enough detail to be acted on.",
    build: "Build the requested output. Return the implementation as one complete, runnable file in a fenced code block. For UI pages, prefer a complete React JSX component when React is implied, otherwise a complete HTML file with embedded CSS and JavaScript. Do not return only a plan.",
    review: "Review the request for issues, risks, improvements, and missing pieces. Lead with actionable findings.",
    fix: "Diagnose the problem and provide the fix with exact steps or code changes. Be specific.",
    learn: "Teach the concept clearly with examples and simple explanation.",
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
  const fencePattern = /```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g;
  let match;
  while ((match = fencePattern.exec(content))) {
    const body = match[2].trim();
    if (!body) continue;
    const ext = extensionForFence(match[1], body);
    artifacts.push({ ext, content: body });
  }
  if (!artifacts.length) {
    const htmlMatch = String(content).match(/(?:<!doctype html>\s*)?<html[\s\S]*?<\/html>/i);
    if (htmlMatch) artifacts.push({ ext: "html", content: htmlMatch[0].trim() });
  }
  if (!artifacts.length && /<!doctype html|<html[\s>]|import React|from\s+['"]react['"]|useState|className=|function\s+\w+|const\s+\w+\s*=/.test(content)) {
    const ext = extensionForFence("", content);
    artifacts.push({ ext, content: content.trim() });
  }
  return artifacts.map((artifact, index) => ({
    ...artifact,
    name: `${artifactBaseName(message)}${artifacts.length > 1 ? `-${index + 1}` : ""}.${artifact.ext}`,
  }));
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
  if (!["build", "fix"].includes(mode)) return [];
  if (!setting(db, "localWritesEnabled", true)) return [];
  const artifacts = extractArtifacts(content, message);
  if (!artifacts.length) return [];
  const root = path.resolve(String(workspace?.workspaceRoot || workspaceRoot(db)));
  const targetDir = path.join(root, "olla-nest-output");
  fs.mkdirSync(targetDir, { recursive: true });
  return artifacts.map((artifact) => {
    const filePath = path.join(targetDir, artifact.name);
    fs.writeFileSync(filePath, `${artifact.content}\n`, "utf8");
    return {
      name: artifact.name,
      path: filePath,
      relativePath: path.relative(root, filePath),
      bytes: Buffer.byteLength(artifact.content, "utf8"),
    };
  });
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
      title: "New workspace",
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
    anthropicEnabled: setting(db, "anthropicEnabled", "false") === "true",
    anthropicApiKey: setting(db, "anthropicApiKey", "") ? "set" : "",
    anthropicBaseUrl: setting(db, "anthropicBaseUrl", ""),
    openaiEnabled: setting(db, "openaiEnabled", "false") === "true",
    openaiApiKey: setting(db, "openaiApiKey", "") ? "set" : "",
    openaiBaseUrl: setting(db, "openaiBaseUrl", ""),
    groqEnabled: setting(db, "groqEnabled", "false") === "true",
    groqApiKey: setting(db, "groqApiKey", "") ? "set" : "",
    customEnabled: setting(db, "customEnabled", "false") === "true",
    customName: setting(db, "customName", ""),
    customApiKey: setting(db, "customApiKey", "") ? "set" : "",
    customBaseUrl: setting(db, "customBaseUrl", ""),
    sqlProvider: setting(db, "sqlProvider", "sqlite"),
    documentProvider: setting(db, "documentProvider", "json-document-store"),
    realtimeProvider: setting(db, "realtimeProvider", "in-memory"),
  };
}

function storageConfig() {
  return {
    mode: STORAGE_MODE,
    recommendedProduction: {
      sql: {
        provider: "postgresql",
        role: "Structural core and source of truth",
        url: DATABASE_URL.replace(/:\/\/([^:]+):([^@]+)@/, "://$1:***@"),
        responsibilities: ["users", "groups", "departments", "permissions", "model registry", "agent state", "billing", "pgvector RAG"],
      },
      document: {
        provider: "mongodb",
        role: "Cognitive archive and long-term memory",
        uri: MONGODB_URI.replace(/:\/\/([^:]+):([^@]+)@/, "://$1:***@"),
        responsibilities: ["chat history", "thought traces", "tool outputs", "unstructured AI artifacts"],
      },
      realtime: {
        provider: "redis",
        role: "Real-time nerve system",
        url: REDIS_URL.replace(/:\/\/([^:]+):([^@]+)@/, "://$1:***@"),
        responsibilities: ["token streaming", "session state", "rate limits", "queues", "pub/sub"],
      },
    },
    localDevelopment: {
      sql: { provider: "sqlite", path: SQL_PATH },
      document: { provider: "json-document-store", path: DOC_PATH },
      realtime: { provider: "in-memory" },
    },
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
    const row = one(db, "SELECT id, name, email, password_hash, role, rights, department_id AS departmentId, active FROM users WHERE email = ? AND active = 1", email);
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
    const row = one(db, "SELECT id, name, email, password_hash, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", req.user.id);
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
    const user = publicUser(one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", req.user.id));
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
      dbConfig: storageConfig(),
      workspace: workspaceForUser(db, user.id),
    });
  } finally {
    db.close();
  }
});

app.get("/api/storage/architecture", requireAuth, (req, res) => {
  res.json(storageConfig());
});

app.post("/api/switch-user", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const user = one(db, "SELECT id, name, email, role, department_id AS departmentId, active FROM users WHERE id = ?", req.body.userId);
    if (!user) return res.status(404).json({ error: "User not found" });
    setSetting(db, "activeUserId", user.id);
    req.user = publicUser(user);
    res.json({ ok: true });
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

app.post("/api/workspace/local-settings", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const workspaceRootInput = String(req.body.workspaceRoot || "").trim();
    if (!workspaceRootInput) return res.status(400).json({ error: "Workspace folder is required" });
    const nextRoot = path.resolve(workspaceRootInput);
    const permissionMode = normalizePermissionMode(req.body.permissionMode);
    fs.mkdirSync(nextRoot, { recursive: true });
    const docs = readDocs();
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
    const user = publicUser(one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", req.user.id));
    if (!hasRight(user, "chat:use")) return res.status(403).json({ error: "Chat access is not enabled for this account" });
    const { message, mode = "ask", manualModelId } = req.body;
    if (!message || !message.trim()) return res.status(400).json({ error: "Message is required" });
    await syncOllamaModels(db).catch(() => []);

    const manualModel = manualModelId ? allowedModels(db, user).find((model) => model.id === manualModelId) : null;
    const route = manualModel
      ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model." }
      : routeModel(db, user, message, mode);

    if (!route.selected) return res.status(403).json({ error: route.reason });

    let content;
    let live = true;
    try {
      if (route.selected.provider !== "ollama") throw new Error("API connector is not configured in this MVP.");
      content = await ollamaGenerate(db, route.selected.model, modelPrompt(message, mode, route));
    } catch (error) {
      live = false;
      content = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${error.message}\n\nThe route itself is valid; check model availability, startup time, or admin configuration.`;
    }

    const workspace = workspaceForUser(db, user.id);
    const localWorkMode = ["build", "fix"].includes(mode);
    const writeApproved = Boolean(req.body.writeToWorkspace) || workspace.permissionMode === "full";
    const shouldWriteLocal = live && localWorkMode && workspace.localWritesEnabled && writeApproved;
    const artifacts = shouldWriteLocal ? writeLocalArtifacts(db, workspace, message, mode, content) : [];
    if (artifacts.length) {
      content = `Done. I created the local file${artifacts.length === 1 ? "" : "s"} in your selected workspace.\n\nLocal files updated:\n${artifacts.map((artifact) => `- ${artifact.path}`).join("\n")}`;
    } else if (live && localWorkMode && !writeApproved) {
      content += `\n\nLocal files not written. Choose a workspace folder and approve local file writes for this request.`;
    } else if (live && localWorkMode && !workspace.localWritesEnabled) {
      content += `\n\nLocal files not written. Admin has disabled local Build/Fix writes.`;
    }

    const docs = readDocs();
    const chat = chatFor(user.id);
    const now = new Date().toISOString();
    chat.messages.push({ role: "user", content: message, mode, createdAt: now });
    chat.messages.push({
      role: "assistant",
      content,
      modelId: route.selected.id,
      modelName: route.selected.name,
      routeReason: route.reason,
      live,
      artifacts,
      createdAt: now,
    });
    docs.chats[user.id] = chat;
    writeDocs(docs);
    appendTrace({ userId: user.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live, artifacts, workspace });
    appendAudit(user.name, "chat.request", `${mode.toUpperCase()} routed to ${route.selected.name}`, { live, artifacts });
    res.json({ content, route, model: route.selected, live, artifacts, chat });
  } finally {
    db.close();
  }
});

app.post("/api/chat/clear", requireAuth, (req, res) => {
  const db = openSql();
  try {
    const user = req.user;
    const docs = readDocs();
    delete docs.chats[user.id];
    writeDocs(docs);
    chatFor(user.id);
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
    const { name, email, role = "user", departmentId = "dept-general", rights = ["chat:use"], password } = req.body;
    if (!name || !email) return res.status(400).json({ error: "Name and email are required" });
    const id = uid("u");
    const passwordHash = bcrypt.hashSync(String(password || DEFAULT_USER_PASSWORD), 12);
    db.prepare("INSERT INTO users (id, name, email, password_hash, role, rights, department_id, active) VALUES (?, ?, ?, ?, ?, ?, ?, 1)").run(
      id,
      name,
      email,
      passwordHash,
      role,
      JSON.stringify(rights),
      departmentId
    );
    db.prepare("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, ?)").run(id, "group-all");
    appendAudit(req.user.name, "admin.user.create", `Created user ${email}`);
    res.json({ ok: true, user: publicUser(one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", id)) });
  } catch (error) {
    res.status(400).json({ error: error.message });
  } finally {
    db.close();
  }
});

app.patch("/api/admin/users/:id", requireAdmin, (req, res) => {
  const db = openSql();
  try {
    const existing = one(db, "SELECT id FROM users WHERE id = ?", req.params.id);
    if (!existing) return res.status(404).json({ error: "User not found" });
    const { name, email, role, departmentId, active, rights } = req.body;
    if (typeof name !== "undefined") db.prepare("UPDATE users SET name = ? WHERE id = ?").run(name, req.params.id);
    if (typeof email !== "undefined") db.prepare("UPDATE users SET email = ? WHERE id = ?").run(email, req.params.id);
    if (typeof role !== "undefined") db.prepare("UPDATE users SET role = ? WHERE id = ?").run(role, req.params.id);
    if (typeof departmentId !== "undefined") db.prepare("UPDATE users SET department_id = ? WHERE id = ?").run(departmentId, req.params.id);
    if (typeof active !== "undefined") db.prepare("UPDATE users SET active = ? WHERE id = ?").run(active ? 1 : 0, req.params.id);
    if (Array.isArray(rights)) db.prepare("UPDATE users SET rights = ? WHERE id = ?").run(JSON.stringify(rights), req.params.id);
    appendAudit(req.user.name, "admin.user.update", `Updated user ${req.params.id}`);
    res.json({ ok: true, user: publicUser(one(db, "SELECT id, name, email, role, rights, department_id AS departmentId, active FROM users WHERE id = ?", req.params.id)) });
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
