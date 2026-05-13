const express = require("express");
const fs = require("fs");
const path = require("path");

const app = express();
const PORT = process.env.PORT || 3000;
const OLLAMA_URL = process.env.OLLAMA_URL || "http://localhost:11434";
const DATA_DIR = path.join(__dirname, "data");
const DB_PATH = path.join(DATA_DIR, "db.json");

app.use(express.json({ limit: "2mb" }));
app.use(express.static(path.join(__dirname, "public")));

function defaultModels() {
  return [
    {
      id: "m-qwen35",
      name: "Qwen 3.5 9B",
      provider: "ollama",
      model: "qwen3.5:9b",
      status: "configured",
      strengths: ["coding", "debugging", "reasoning", "analysis", "build", "review", "project"],
      speed: "medium",
      privacy: "local",
    },
    {
      id: "m-gemma4",
      name: "Gemma 4 26B",
      provider: "ollama",
      model: "gemma4:26b",
      status: "configured",
      strengths: ["reasoning", "writing", "analysis", "summary", "learn", "general", "complex"],
      speed: "slow",
      privacy: "local",
    },
    {
      id: "m-granite41",
      name: "Granite 4.1 3B",
      provider: "ollama",
      model: "granite4.1:3b",
      status: "configured",
      strengths: ["general", "summary", "operations", "ask", "writing"],
      speed: "fast",
      privacy: "local",
    },
    {
      id: "m-lfm-thinking",
      name: "LFM 2.5 Thinking 1.2B",
      provider: "ollama",
      model: "lfm2.5-thinking:1.2b",
      status: "configured",
      strengths: ["reasoning", "analysis", "learn", "ask"],
      speed: "fast",
      privacy: "local",
    },
    {
      id: "m-medgemma",
      name: "MedGemma 4B",
      provider: "ollama",
      model: "medgemma:4b",
      status: "configured",
      strengths: ["medical", "analysis", "summary", "learn"],
      speed: "medium",
      privacy: "local",
    },
    {
      id: "m-glm-ocr",
      name: "GLM OCR BF16",
      provider: "ollama",
      model: "glm-ocr:bf16",
      status: "configured",
      strengths: ["ocr", "vision", "document", "analysis"],
      speed: "medium",
      privacy: "local",
    },
    {
      id: "m-api-premium",
      name: "Premium API Model",
      provider: "api",
      model: "connected-premium",
      status: "disabled",
      strengths: ["reasoning", "analysis", "complex"],
      speed: "medium",
      privacy: "cloud",
    },
  ];
}

function seedDb() {
  return {
    activeUserId: "u-admin",
    settings: {
      routerEnabled: true,
      allowApiModels: false,
      requireApprovalForCommands: true,
      localOnlyDefault: true,
    },
    departments: [
      { id: "dept-eng", name: "Engineering", policyId: "policy-dev" },
      { id: "dept-ops", name: "Operations", policyId: "policy-ops" },
      { id: "dept-hr", name: "People", policyId: "policy-writing" },
    ],
    policies: [
      {
        id: "policy-dev",
        name: "Developer Policy",
        description: "Coding, review, fix, project analysis, and local-first execution.",
        allowedModelIds: ["m-qwen35", "m-gemma4", "m-granite41", "m-lfm-thinking"],
        allowedModes: ["ask", "build", "review", "fix", "learn"],
      },
      {
        id: "policy-writing",
        name: "Writing Policy",
        description: "Business writing, documentation, HR, and communication tasks.",
        allowedModelIds: ["m-gemma4", "m-granite41", "m-lfm-thinking"],
        allowedModes: ["ask", "learn"],
      },
      {
        id: "policy-ops",
        name: "Operations Policy",
        description: "General analysis, summaries, and controlled internal operations help.",
        allowedModelIds: ["m-granite41", "m-lfm-thinking", "m-gemma4", "m-glm-ocr"],
        allowedModes: ["ask", "review", "learn"],
      },
      {
        id: "policy-health",
        name: "Healthcare Policy",
        description: "Medical-domain summaries and learning with local-only access.",
        allowedModelIds: ["m-medgemma", "m-gemma4", "m-lfm-thinking"],
        allowedModes: ["ask", "review", "learn"],
      },
    ],
    users: [
      { id: "u-admin", name: "Admin", role: "admin", departmentId: "dept-eng", directModelIds: ["m-api-premium"] },
      { id: "u-dev", name: "Dev Employee", role: "user", departmentId: "dept-eng", directModelIds: [] },
      { id: "u-ops", name: "Ops Employee", role: "user", departmentId: "dept-ops", directModelIds: [] },
      { id: "u-hr", name: "People Employee", role: "user", departmentId: "dept-hr", directModelIds: [] },
    ],
    models: defaultModels(),
    chats: [
      {
        id: "c-welcome",
        userId: "u-dev",
        title: "Welcome workspace",
        messages: [
          {
            role: "assistant",
            content:
              "Welcome to Olla Nest. Ask once, and Auto Router will choose the best approved model for your task.",
            modelName: "System",
            createdAt: new Date().toISOString(),
          },
        ],
      },
    ],
    audit: [],
  };
}

function ensureDb() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DB_PATH)) fs.writeFileSync(DB_PATH, JSON.stringify(seedDb(), null, 2));
  else {
    const db = JSON.parse(fs.readFileSync(DB_PATH, "utf8"));
    const hasCurrentCatalog = db.models?.some((model) => model.id === "m-qwen35");
    if (!hasCurrentCatalog) {
      const fresh = seedDb();
      fresh.activeUserId = db.activeUserId || fresh.activeUserId;
      fresh.audit = db.audit || [];
      fresh.chats = db.chats || fresh.chats;
      fs.writeFileSync(DB_PATH, JSON.stringify(fresh, null, 2));
    }
  }
}

function readDb() {
  ensureDb();
  return JSON.parse(fs.readFileSync(DB_PATH, "utf8"));
}

function writeDb(db) {
  fs.writeFileSync(DB_PATH, JSON.stringify(db, null, 2));
}

function uid(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function userPolicy(db, user) {
  const dept = db.departments.find((d) => d.id === user.departmentId);
  return db.policies.find((p) => p.id === dept?.policyId);
}

function allowedModelIds(db, user) {
  if (user.role === "admin") return db.models.map((m) => m.id);
  const policy = userPolicy(db, user);
  return Array.from(new Set([...(policy?.allowedModelIds || []), ...(user.directModelIds || [])]));
}

function allowedModels(db, user) {
  const ids = allowedModelIds(db, user);
  return db.models.filter((m) => {
    if (!ids.includes(m.id) || m.status === "disabled") return false;
    if (m.provider === "api" && !db.settings.allowApiModels) return false;
    return true;
  });
}

function classifyRequest(message, mode = "ask") {
  const text = message.toLowerCase();
  const tags = [];
  if (/(medical|health|doctor|patient|clinical|medicine|diagnosis|symptom)/.test(text)) tags.push("medical", "analysis");
  if (/(ocr|image|scan|receipt|invoice|extract text|read this image|document image)/.test(text)) tags.push("ocr", "vision", "document");
  if (/(operations|weekly update|status update|process|workflow|ops)/.test(text)) tags.push("operations", "summary");
  if (/(bug|error|fix|stack|trace|failing|broken|debug)/.test(text) || mode === "fix") tags.push("fix", "debugging", "coding");
  if (/(code|repo|component|api|server|frontend|backend|function|test|build)/.test(text) || mode === "build") tags.push("coding", "build", "project");
  if (/(review|risk|issue|security|quality|audit)/.test(text) || mode === "review") tags.push("review", "analysis");
  if (/(write|email|doc|document|pitch|copy|summarize|summary)/.test(text)) tags.push("writing", "summary");
  if (/(explain|teach|learn|understand|what is|how does)/.test(text) || mode === "learn") tags.push("learn", "general");
  if (!tags.length) tags.push("general", "ask");
  return Array.from(new Set(tags));
}

function routeModel(db, user, message, mode) {
  const candidates = allowedModels(db, user);
  if (!db.settings.routerEnabled) {
    return {
      selected: candidates[0],
      tags: ["router-disabled"],
      candidates: candidates.map((model) => ({ id: model.id, name: model.name, score: 0 })),
      reason: "Auto Router is disabled by admin, so the first approved active model was selected.",
    };
  }
  const tags = classifyRequest(message, mode);
  const scored = candidates.map((model) => {
    const domainScore = ["medical", "ocr", "vision"].some((tag) => tags.includes(tag) && model.strengths.includes(tag)) ? 45 : 0;
    const matchScore = model.strengths.filter((s) => tags.includes(s)).length * 10;
    const localScore = model.privacy === "local" ? 3 : db.settings.allowApiModels ? 1 : -20;
    const speedScore = model.speed === "fast" ? 7 : model.speed === "medium" ? 3 : 0;
    return { model, score: domainScore + matchScore + localScore + speedScore };
  });
  scored.sort((a, b) => b.score - a.score);
  const selected = scored[0]?.model;
  return {
    selected,
    tags,
    candidates: scored.map((s) => ({ id: s.model.id, name: s.model.name, score: s.score })),
    reason: selected
      ? `Matched ${tags.join(", ")} to ${selected.name} from the models approved for ${user.name}.`
      : "No approved active model is available for this user.",
  };
}

async function ollamaGenerate(model, prompt) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 45000);
  const response = await fetch(`${OLLAMA_URL}/api/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    signal: controller.signal,
    body: JSON.stringify({
      model,
      prompt,
      stream: false,
      options: { temperature: 0.7, num_predict: 220 },
    }),
  });
  clearTimeout(timeout);
  if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
  const data = await response.json();
  return data.response || "";
}

function fallbackAnswer(model, message, route) {
  return `Auto Router selected ${model.name} for this request.\n\nOllama is not reachable or this model is not downloaded yet, so this is a product-mode response instead of a live model answer.\n\nRequest type detected: ${route.tags.join(", ")}\n\nNext action: ask the admin to download or enable the selected model, or use Admin Panel > Models to map this task to an installed local model.`;
}

app.get("/api/state", (req, res) => {
  const db = readDb();
  const activeUser = db.users.find((u) => u.id === db.activeUserId) || db.users[0];
  res.json({
    activeUser,
    users: db.users,
    departments: db.departments,
    policies: db.policies,
    models: db.models,
    settings: db.settings,
    chats: db.chats.filter((c) => c.userId === activeUser.id || activeUser.role === "admin"),
    audit: db.audit.slice(-30).reverse(),
    allowedModelIds: allowedModelIds(db, activeUser),
  });
});

app.post("/api/switch-user", (req, res) => {
  const db = readDb();
  const user = db.users.find((u) => u.id === req.body.userId);
  if (!user) return res.status(404).json({ error: "User not found" });
  db.activeUserId = user.id;
  writeDb(db);
  res.json({ ok: true });
});

app.get("/api/ollama/models", async (req, res) => {
  try {
    const response = await fetch(`${OLLAMA_URL}/api/tags`);
    if (!response.ok) throw new Error(`Ollama returned ${response.status}`);
    const data = await response.json();
    res.json({ ok: true, models: data.models || [] });
  } catch (error) {
    res.json({ ok: false, error: error.message, models: [] });
  }
});

app.post("/api/chat", async (req, res) => {
  const db = readDb();
  const user = db.users.find((u) => u.id === db.activeUserId);
  if (!user) return res.status(401).json({ error: "No active user" });
  const { message, mode = "ask", manualModelId } = req.body;
  if (!message || !message.trim()) return res.status(400).json({ error: "Message is required" });

  const route = manualModelId
    ? { selected: allowedModels(db, user).find((m) => m.id === manualModelId), tags: ["manual"], candidates: [], reason: "User manually selected a model." }
    : routeModel(db, user, message, mode);

  if (!route.selected) return res.status(403).json({ error: route.reason });

  let content;
  let live = true;
  try {
    if (route.selected.provider !== "ollama") throw new Error("API connector is not configured in MVP.");
    content = await ollamaGenerate(route.selected.model, message);
  } catch (error) {
    live = false;
    content = fallbackAnswer(route.selected, message, route);
  }

  let chat = db.chats.find((c) => c.userId === user.id);
  if (!chat) {
    chat = { id: uid("chat"), userId: user.id, title: "New workspace", messages: [] };
    db.chats.push(chat);
  }
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
  db.audit.push({
    id: uid("audit"),
    actor: user.name,
    action: "chat.request",
    detail: `${mode.toUpperCase()} routed to ${route.selected.name}`,
    createdAt: now,
  });
  writeDb(db);
  res.json({ content, route, model: route.selected, live, chat });
});

app.post("/api/chat/clear", (req, res) => {
  const db = readDb();
  const user = db.users.find((u) => u.id === db.activeUserId);
  if (!user) return res.status(401).json({ error: "No active user" });
  db.chats = db.chats.filter((chat) => chat.userId !== user.id);
  db.chats.push({
    id: uid("chat"),
    userId: user.id,
    title: "New workspace",
    messages: [
      {
        role: "assistant",
        content: "Ready. Ask once, and Auto Router will choose the best approved model for your task.",
        modelName: "Olla Nest",
        createdAt: new Date().toISOString(),
      },
    ],
  });
  writeDb(db);
  res.json({ ok: true });
});

app.post("/api/admin/model", (req, res) => {
  const db = readDb();
  const actor = db.users.find((u) => u.id === db.activeUserId);
  if (actor?.role !== "admin") return res.status(403).json({ error: "Admin access required" });
  const model = { ...req.body, id: req.body.id || uid("m"), strengths: req.body.strengths || [] };
  const index = db.models.findIndex((m) => m.id === model.id);
  if (index >= 0) db.models[index] = { ...db.models[index], ...model };
  else db.models.push(model);
  db.audit.push({ id: uid("audit"), actor: actor.name, action: "admin.model.save", detail: model.name, createdAt: new Date().toISOString() });
  writeDb(db);
  res.json({ ok: true, model });
});

app.post("/api/admin/policy", (req, res) => {
  const db = readDb();
  const actor = db.users.find((u) => u.id === db.activeUserId);
  if (actor?.role !== "admin") return res.status(403).json({ error: "Admin access required" });
  const policy = { ...req.body, id: req.body.id || uid("policy") };
  const index = db.policies.findIndex((p) => p.id === policy.id);
  if (index >= 0) db.policies[index] = { ...db.policies[index], ...policy };
  else db.policies.push(policy);
  db.audit.push({ id: uid("audit"), actor: actor.name, action: "admin.policy.save", detail: policy.name, createdAt: new Date().toISOString() });
  writeDb(db);
  res.json({ ok: true, policy });
});

app.post("/api/admin/settings", (req, res) => {
  const db = readDb();
  const actor = db.users.find((u) => u.id === db.activeUserId);
  if (actor?.role !== "admin") return res.status(403).json({ error: "Admin access required" });
  db.settings = { ...db.settings, ...req.body };
  db.audit.push({ id: uid("audit"), actor: actor.name, action: "admin.settings.save", detail: "Updated system settings", createdAt: new Date().toISOString() });
  writeDb(db);
  res.json({ ok: true, settings: db.settings });
});

app.get("*", (req, res) => {
  res.sendFile(path.join(__dirname, "public", "index.html"));
});

app.listen(PORT, () => {
  ensureDb();
  console.log(`Olla Nest running at http://localhost:${PORT}`);
});
