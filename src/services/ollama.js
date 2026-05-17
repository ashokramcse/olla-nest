/**
 * @file src/services/ollama.js
 * @description Ollama service: ollamaUrl, cleanBaseUrl, fetchOllamaModels, syncOllamaModels,
 * fetchOllamaModelInfo, ollamaGenerate, upsertModel, inferCapabilities, inferScores, ensureDefaultAccess.
 */

const { OLLAMA_URL } = require("../config");
const { setting } = require("../db/settings");
const { encryptKey } = require("./crypto");

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
    const { uid } = require("../models/model");
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
  const { uid } = require("../models/model");
  const models = db.prepare("SELECT id FROM models WHERE provider = 'ollama'").all();
  const grants = db.prepare("SELECT COUNT(*) AS count FROM access_grants").get().count;
  if (grants > 0 || models.length === 0) return;
  const insert = db.prepare("INSERT INTO access_grants (id, subject_type, subject_id, model_id, can_use) VALUES (?, ?, ?, ?, 1)");
  for (const model of models) {
    insert.run(uid("grant"), "group", "group-all", model.id);
  }
}

async function ollamaGenerate(db, model, prompt) {
  const { callProvider } = require("./providers");
  const provider = { type: "ollama", base_url: ollamaUrl(db), api_key_enc: encryptKey(""), name: "Ollama" };
  const messages = [{ role: "user", content: prompt }];
  const result = await callProvider(provider, model, messages, { timeout: 300000 });
  return result.content;
}

module.exports = {
  ollamaUrl,
  cleanBaseUrl,
  fetchOllamaModels,
  syncOllamaModels,
  fetchOllamaModelInfo,
  ollamaGenerate,
  upsertModel,
  inferCapabilities,
  inferScores,
  ensureDefaultAccess,
};
