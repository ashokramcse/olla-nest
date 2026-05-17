/**
 * @file src/models/model.js
 * @description Model helpers: parseModel, uid, safeJson, readDocs, writeDocs.
 */

const fs = require("fs");
const { DOC_PATH } = require("../config");
const { ensureDataDir } = require("../db/index");

/**
 * Generates a collision-resistant, human-readable ID.
 *
 * Format: `{prefix}-{base36 timestamp}-{6 random chars}`
 * Example: "msg-lrk4z2-a8xf3q"
 *
 * This is not a UUID — it is intentionally shorter and sortable by
 * insertion time (the timestamp component is the dominant part).
 *
 * @param {string} prefix - Short string to prepend (e.g. "msg", "chat", "u").
 * @returns {string} A unique ID string.
 */
function uid(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Parses a JSON string, returning `fallback` instead of throwing on invalid input.
 * Used throughout to safely read JSON columns from SQLite (rights, capabilities, etc.).
 *
 * @param {string|null} value - JSON string to parse.
 * @param {*} fallback - Returned when value is null, empty, or malformed.
 * @returns {*} Parsed value or fallback.
 */
function safeJson(value, fallback) {
  try {
    return JSON.parse(value || "");
  } catch {
    return fallback;
  }
}

/**
 * Maps a raw models table row to the camelCase object shape used in API responses.
 * Parses the capabilities JSON array and normalises numeric fields.
 *
 * @param {object} row - Raw SQLite row from the models table.
 * @returns {object} Parsed model descriptor.
 */
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
    contextSize: Number(row.context_size || row.max_context_size || 0) || null,
    externalCostTier: row.external_cost_tier || "local-free",
    sensitiveAllowed: row.sensitive_allowed !== 0,
  };
}

/**
 * Reads (or initialises) the legacy documents.json flat-file store.
 *
 * This file was the original persistence layer before SQLite was introduced.
 * It is kept alive only to support the one-time migrateDocumentsJson() call on
 * startup.  After migration it is renamed to documents.json.migrated and this
 * function effectively becomes a no-op.
 *
 * @returns {{ chats: object, audit: Array, routerTraces: Array, workspacePrefs: object }} Parsed doc store.
 */
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

/**
 * Persists the in-memory documents object back to documents.json.
 * @param {object} docs - The full document store object returned by readDocs().
 */
function writeDocs(docs) {
  fs.writeFileSync(DOC_PATH, JSON.stringify(docs, null, 2));
}

module.exports = { parseModel, uid, safeJson, readDocs, writeDocs };
