/**
 * @file src/services/providers.js
 * @description Provider call layer: callProvider, callProviderStream, resolveProvider, mirrorApiModelToModels.
 */

const { OLLAMA_URL } = require("../config");
const { decryptKey, encryptKey } = require("./crypto");
const { cleanBaseUrl, ollamaUrl } = require("./ollama");
const { cleanModelOutput } = require("./workspace");

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

module.exports = { callProvider, callProviderStream, resolveProvider, mirrorApiModelToModels };
