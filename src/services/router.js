/**
 * @file src/services/router.js
 * @description Auto Router: classifyRequest, routeModel, detectSensitiveContent.
 */

const { setting } = require("../db/settings");
const { safeJson } = require("../models/model");

// ─── Sensitive content detection ───────────────────────────────────────────────
/**
 * Scans a message for sensitive content that should never be routed to an external provider.
 *
 * Built-in patterns detect: SSNs, credit card numbers, API key prefixes, and medical/PHI terms.
 * Admins can extend this list via the sensitivePatterns setting (an array of regex strings).
 *
 * @param {string} text - The message to scan.
 * @param {DatabaseSync|null} db - Optional open handle for reading admin patterns.
 * @returns {{ isSensitive: boolean, reasons: string[] }}
 */
function detectSensitiveContent(text, db) {
  const reasons = [];
  const builtinPatterns = [
    { pattern: /\b\d{3}-\d{2}-\d{4}\b/, label: "SSN" },
    { pattern: /\b\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}\b/, label: "credit card" },
    { pattern: /sk-[a-zA-Z0-9]{20,}/, label: "API key" },
    { pattern: /diagnosis|prescription|patient\s+record|PHI|HIPAA/i, label: "medical/PHI" },
  ];
  for (const { pattern, label } of builtinPatterns) {
    if (pattern.test(text)) reasons.push(label);
  }
  try {
    const adminPatterns = db ? safeJson(setting(db, "sensitivePatterns", null), []) : [];
    for (const pat of adminPatterns) {
      try {
        if (new RegExp(pat).test(text)) reasons.push(`admin pattern: ${pat}`);
      } catch {}
    }
  } catch {}
  return { isSensitive: reasons.length > 0, reasons };
}

/**
 * Classifies a user's message into a set of capability tags used by the Auto Router
 * to match against model capabilities.
 *
 * Tags are produced by regex pattern matching on the message text and the `mode`
 * string (e.g. mode="build" always adds the "coding" tag).  If no patterns match,
 * ["general", "ask"] are returned as defaults so there is always at least one tag.
 *
 * @param {string} message - The raw user message.
 * @param {string} [mode="ask"] - Chat mode (ask|build|review|fix|learn|debug|test|docs|plan).
 * @returns {string[]} Array of capability tag strings.
 */
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

/**
 * Core Auto Router: selects the best model from the user's approved models.
 *
 * Scoring formula per candidate:
 *   score = (capabilityMatch * 35)
 *         + (specialistBonus 45 if request has specialist tag model also has)
 *         + speedScore  * weights.speed
 *         + qualityScore * weights.quality
 *         + privacyScore * weights.privacy    (local=100*w, external=0 or -100)
 *
 * Privacy override: if the message contains sensitive content (PII/API keys/PHI)
 * or the mode is in the "localOnlyModes" list (default: build, fix), all external
 * models are removed from the candidate pool before scoring.
 *
 * If routerEnabled=false, the first allowed model is returned without scoring.
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @param {string} message - User's raw message.
 * @param {string} mode - Chat mode string.
 * @returns {{ selected: object|null, tags: string[], candidates: object[],
 *             reason: string, privacyBlocked: boolean, sensitiveReasons: string[] }}
 */
function routeModel(db, user, message, mode) {
  const { allowedModels } = require("../models/user");
  let candidates = allowedModels(db, user);
  const tags = classifyRequest(message, mode);
  const weights = safeJson(setting(db, "routerWeights", null), { speed: 0.3, quality: 0.5, privacy: 0.2 });
  const localOnlyModes = safeJson(setting(db, "localOnlyModes", null), ["build", "fix"]);
  const sensitivityResult = detectSensitiveContent(message, db);
  const privacyBlocked = sensitivityResult.isSensitive || localOnlyModes.includes(mode);

  if (privacyBlocked) {
    candidates = candidates.filter(m => m.privacy === "local");
  }

  if (!setting(db, "routerEnabled", true)) {
    return {
      selected: candidates[0],
      tags: ["router-disabled"],
      candidates: candidates.map((model) => ({ id: model.id, name: model.name, score: 0 })),
      reason: "Auto Router is disabled by admin, so the first approved model was selected.",
      privacyBlocked,
      sensitiveReasons: sensitivityResult.reasons,
    };
  }

  const scored = candidates.map((model) => {
    const matches = model.capabilities.filter((capability) => tags.includes(capability));
    const capabilityScore = matches.length * 35;
    const specialistScore = ["medical", "ocr", "vision", "coding"].some((tag) => tags.includes(tag) && model.capabilities.includes(tag)) ? 45 : 0;
    const speedW = Number(weights.speed) || 0.3;
    const qualityW = Number(weights.quality) || 0.5;
    const privacyW = Number(weights.privacy) || 0.2;
    const privacyScore = model.privacy === "local" ? 100 * privacyW : setting(db, "allowApiModels", false) ? 0 : -100;
    const score = capabilityScore + specialistScore + model.speedScore * speedW + model.qualityScore * qualityW + privacyScore;
    return { model, score: Math.round(score), matches, breakdown: { capabilityMatch: capabilityScore, speedScore: model.speedScore * speedW, qualityScore: model.qualityScore * qualityW, privacyScore, weightedTotal: score } };
  });

  scored.sort((a, b) => b.score - a.score);
  const selected = scored[0]?.model;
  return {
    selected,
    tags,
    candidates: scored.map((item) => ({ id: item.model.id, name: item.model.name, score: item.score, matches: item.matches, breakdown: item.breakdown })),
    reason: selected
      ? `Selected ${selected.name} by matching request capabilities, speed, quality, privacy, and the user's approved access.`
      : "No approved active model is available for this user.",
    privacyBlocked,
    sensitiveReasons: sensitivityResult.reasons,
  };
}

module.exports = { classifyRequest, routeModel, detectSensitiveContent };
