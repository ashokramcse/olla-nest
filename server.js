const express = require("express");
const fs = require("fs");
const path = require("path");
const { DatabaseSync } = require("node:sqlite");

const app = express();
const PORT = process.env.PORT || 3000;
const OLLAMA_URL = process.env.OLLAMA_URL || "http://localhost:11434";
const DATA_DIR = path.join(__dirname, "data");
const SQL_PATH = process.env.SQLITE_PATH || path.join(DATA_DIR, "olla-nest.sqlite");
const DOC_PATH = process.env.DOCUMENT_DB_PATH || path.join(DATA_DIR, "documents.json");

app.use(express.json({ limit: "2mb" }));
app.use(express.static(path.join(__dirname, "public")));

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
      role TEXT NOT NULL,
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
        },
        null,
        2
      )
    );
  }
  return JSON.parse(fs.readFileSync(DOC_PATH, "utf8"));
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
      ["sqlProvider", "sqlite"],
      ["documentProvider", "json-document-store"],
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
    [
      ["u-admin", "Admin", "admin", "dept-product"],
      ["u-user", "Employee", "user", "dept-general"],
      ["u-builder", "Builder Employee", "user", "dept-product"],
      ["u-support", "Support Employee", "user", "dept-support"],
    ].forEach((row) => db.prepare("INSERT INTO users (id, name, role, department_id) VALUES (?, ?, ?, ?)").run(...row));
    [
      ["u-admin", "group-admins"],
      ["u-admin", "group-all"],
      ["u-user", "group-all"],
      ["u-builder", "group-all"],
      ["u-builder", "group-builders"],
      ["u-support", "group-all"],
    ].forEach((row) => db.prepare("INSERT INTO user_groups (user_id, group_id) VALUES (?, ?)").run(...row));
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

async function fetchOllamaModels() {
  const response = await fetch(`${OLLAMA_URL}/api/tags`);
  if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
  const data = await response.json();
  return data.models || [];
}

async function syncOllamaModels(db) {
  const installed = await fetchOllamaModels();
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

function getUsers(db) {
  return rows(db, "SELECT id, name, role, department_id AS departmentId, active FROM users ORDER BY role, name");
}

function activeUser(db) {
  const id = setting(db, "activeUserId", "u-admin");
  return one(db, "SELECT id, name, role, department_id AS departmentId, active FROM users WHERE id = ?", id) || one(db, "SELECT id, name, role, department_id AS departmentId, active FROM users LIMIT 1");
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

async function ollamaGenerate(model, prompt) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 45000);
  try {
    const response = await fetch(`${OLLAMA_URL}/api/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: controller.signal,
      body: JSON.stringify({
        model,
        prompt,
        stream: false,
        options: { temperature: 0.6, num_predict: 220 },
      }),
    });
    if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
    const data = await response.json();
    return data.response || "";
  } finally {
    clearTimeout(timeout);
  }
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
    sqlProvider: setting(db, "sqlProvider", "sqlite"),
    documentProvider: setting(db, "documentProvider", "json-document-store"),
  };
}

app.get("/api/state", async (req, res) => {
  const db = openSql();
  try {
    await syncOllamaModels(db).catch(() => []);
    const user = activeUser(db);
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
      dbConfig: {
        sql: { provider: "sqlite", path: SQL_PATH },
        document: { provider: "json-document-store", path: DOC_PATH },
        configurable: true,
      },
    });
  } finally {
    db.close();
  }
});

app.post("/api/switch-user", (req, res) => {
  const db = openSql();
  try {
    const user = one(db, "SELECT id FROM users WHERE id = ?", req.body.userId);
    if (!user) return res.status(404).json({ error: "User not found" });
    setSetting(db, "activeUserId", user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.get("/api/ollama/models", async (req, res) => {
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

app.post("/api/chat", async (req, res) => {
  const db = openSql();
  try {
    const user = activeUser(db);
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
      content = await ollamaGenerate(route.selected.model, message);
    } catch (error) {
      live = false;
      content = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${error.message}\n\nThe route itself is valid; check model availability, startup time, or admin configuration.`;
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
      createdAt: now,
    });
    docs.chats[user.id] = chat;
    writeDocs(docs);
    appendTrace({ userId: user.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live });
    appendAudit(user.name, "chat.request", `${mode.toUpperCase()} routed to ${route.selected.name}`, { live });
    res.json({ content, route, model: route.selected, live, chat });
  } finally {
    db.close();
  }
});

app.post("/api/chat/clear", (req, res) => {
  const db = openSql();
  try {
    const user = activeUser(db);
    const docs = readDocs();
    delete docs.chats[user.id];
    writeDocs(docs);
    chatFor(user.id);
    res.json({ ok: true });
  } finally {
    db.close();
  }
});

app.post("/api/admin/settings", (req, res) => {
  const db = openSql();
  try {
    const user = activeUser(db);
    if (user.role !== "admin") return res.status(403).json({ error: "Admin access required" });
    ["routerEnabled", "allowApiModels", "localOnlyDefault"].forEach((key) => {
      if (typeof req.body[key] !== "undefined") setSetting(db, key, req.body[key]);
    });
    appendAudit(user.name, "admin.settings.save", "Updated system settings");
    res.json({ ok: true, settings: settingsState(db) });
  } finally {
    db.close();
  }
});

app.get("*", (req, res) => {
  res.sendFile(path.join(__dirname, "public", "index.html"));
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
