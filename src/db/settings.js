/**
 * @file src/db/settings.js
 * @description Settings helpers: setting(), setSetting(), settingsState(), tableCount().
 */

const { OLLAMA_URL, DEFAULT_WORKSPACE_ROOT } = require("../config");

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
function setting(db, key, fallback) {
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
function setSetting(db, key, value) {
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").run(key, String(value));
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

function settingsState(db) {
  const { safeJson } = require("../models/model");
  const { ollamaUrl } = require("../services/ollama");
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
    routerWeights: safeJson(setting(db, "routerWeights", null), { speed: 0.3, quality: 0.5, privacy: 0.2 }),
    sensitivePatterns: safeJson(setting(db, "sensitivePatterns", null), []),
    localOnlyModes: safeJson(setting(db, "localOnlyModes", null), ["build", "fix"]),
    projectKnowledge: setting(db, "projectKnowledge", ""),
  };
}

module.exports = { setting, setSetting, tableCount, settingsState };
