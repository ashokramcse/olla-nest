/**
 * @file src/routes/admin/providers.js
 * @description Admin provider routes: /api/admin/providers (all CRUD + test + sync + model approval).
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAdmin } = deps;
  const { encryptKey, decryptKey } = deps;
  const { cleanBaseUrl } = deps;
  const { callProvider } = deps;
  const { mirrorApiModelToModels } = deps;
  const { appendAudit } = deps;
  const { uid } = deps;

  // [GET] /api/admin/providers — Auth: requireAdmin — Purpose: list all external AI providers with model counts
  router.get("/", requireAdmin, (req, res) => {
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
  router.post("/", requireAdmin, (req, res) => {
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
  router.put("/:id", requireAdmin, (req, res) => {
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
  router.delete("/:id", requireAdmin, (req, res) => {
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
  router.post("/:id/test", requireAdmin, async (req, res) => {
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

  // [POST] /api/admin/providers/:id/sync — Auth: requireAdmin — Purpose: fetch model list from provider API and upsert into api_models
  router.post("/:id/sync", requireAdmin, async (req, res) => {
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
  router.get("/:id/models", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const models = db.prepare("SELECT * FROM api_models WHERE provider_id = ? ORDER BY display_name").all(req.params.id);
      res.json({ ok: true, models: models.map(m => ({ ...m, isApproved: Boolean(m.is_approved) })) });
    } finally {
      db.close();
    }
  });

  // [PUT] /api/admin/providers/:id/models/:modelId — Auth: requireAdmin — Purpose: approve/restrict a model and mirror to main models table
  router.put("/:id/models/:modelId", requireAdmin, (req, res) => {
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

  return router;
};
