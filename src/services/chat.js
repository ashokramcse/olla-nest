/**
 * @file src/services/chat.js
 * @description Chat helpers: estimateTokens, getModelContextWindow, buildContextMessages,
 * buildSystemPrompt, modelPrompt, buildChatObject, parseMessage, getActiveChatSession,
 * getActiveChat, archiveCurrentChat, chatFor, appendAudit, appendTrace.
 */

const fs = require("fs");
const { DOC_PATH } = require("../config");
const { openSql } = require("../db/index");
const { safeJson, uid } = require("../models/model");
const { listWorkspaceFiles } = require("./workspace");

// ─── Context window helpers ───────────────────────────────────────────────────

/**
 * Rough token count estimate: ~4 characters per token (industry rule of thumb for
 * English text with common code).  Used for context budget calculations where an
 * exact BPE tokeniser is unavailable and speed matters more than precision.
 *
 * @param {string} text
 * @returns {number} Estimated token count.
 */
function estimateTokens(text) {
  return Math.ceil((text || "").length / 4);
}

/**
 * Returns the context window size for a model in tokens.
 * Checks the main models table first (Ollama models populated from /api/show),
 * then the api_models table (external providers), then defaults to 8192.
 *
 * @param {DatabaseSync} db
 * @param {string} modelName - Model name or model_ref string.
 * @returns {number} Context window in tokens.
 */
function getModelContextWindow(db, modelName) {
  // Check main models table first (Ollama models have context_size from /api/show)
  const row = db.prepare("SELECT context_size FROM models WHERE model_ref = ? OR name = ? LIMIT 1").get(modelName, modelName);
  if (row?.context_size && row.context_size > 0) return row.context_size;
  // Fall back to api_models table (external provider models)
  const apiRow = db.prepare("SELECT context_window FROM api_models WHERE model_id = ? LIMIT 1").get(modelName);
  if (apiRow?.context_window && apiRow.context_window > 0) return apiRow.context_window;
  return 8192;
}

/**
 * Builds the messages array to send to a provider, honouring the model's context window.
 *
 * Sliding-window algorithm (newest-first trimming):
 *   Step 1: Look up the model's actual context window from the DB (default 8192 tokens).
 *   Step 2: Reserve tokens for the system prompt, the new user message, and 512 tokens
 *           of response headroom.  What remains is the `budget` for history.
 *   Step 3: Load all prior messages for the session, oldest → newest.
 *   Step 4: Walk the history from newest → oldest, keeping messages that fit in
 *           the remaining budget.  Stop as soon as adding the next message would
 *           exceed the budget.  This preserves the most recent context.
 *   Step 5: Re-reverse the kept messages back to chronological order.
 *   Step 6: Assemble final array: [system, ...kept history, new user message].
 *
 * Images are attached to the final user message (Ollama multimodal format).
 *
 * @param {DatabaseSync} db
 * @param {string} sessionId - chat_sessions.id for the current conversation.
 * @param {string} systemPrompt - System prompt string.
 * @param {string} userMessage - The new user message (not yet in DB).
 * @param {string} modelName - Used to look up context window size.
 * @param {string[]|null} images - Base64 image strings for multimodal requests.
 * @returns {{ role: string, content: string }[]} Messages array ready for the provider.
 */
function buildContextMessages(db, sessionId, systemPrompt, userMessage, modelName, images) {
  const tokenLimit = getModelContextWindow(db, modelName);
  const reservedTokens = estimateTokens(systemPrompt) + estimateTokens(userMessage) + 512;
  let budget = Math.max(0, tokenLimit - reservedTokens);

  // Load all prior messages oldest→newest (current message not yet inserted)
  const history = db.prepare(
    "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC"
  ).all(sessionId);

  // Walk newest→oldest, keep pairs that fit in budget
  const kept = [];
  for (let i = history.length - 1; i >= 0; i--) {
    const tokens = estimateTokens(history[i].content);
    if (budget - tokens < 0) break;
    budget -= tokens;
    kept.unshift(history[i]);
  }

  const userMsg = images && images.length
    ? { role: "user", content: userMessage, images }
    : { role: "user", content: userMessage };

  return [
    { role: "system", content: systemPrompt },
    ...kept.map(m => ({ role: m.role, content: m.content })),
    userMsg,
  ];
}

/**
 * Constructs the system prompt for a given chat request.
 *
 * The prompt always includes:
 *   - Platform identity ("You are Olla Nest")
 *   - Selected model name and routing reason (so the model doesn't answer with only its name)
 *   - An explicit instruction to suppress <think> blocks
 *
 * If the user has a workspace configured, the prompt also includes:
 *   - Active project folder path
 *   - Write permission mode
 *   - Instruction to use ```lang:filename fence format for saved files
 *   - Current project file list (up to 50 files)
 *
 * A mode-specific instruction is appended last (e.g. "build" instructs returning
 * a complete runnable file, "debug" instructs showing the root cause).
 *
 * @param {string} mode - Chat mode string.
 * @param {{ selected: { name: string }, reason: string }} route - Router result.
 * @param {{ workspaceRoot?: string, permissionMode?: string }|null} workspace
 * @returns {string} Full system prompt.
 */
function buildSystemPrompt(mode, route, workspace) {
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
  return `${base.join("\n")}\nMode: ${mode}\nInstruction: ${modeInstructions[mode] || modeInstructions.ask}`;
}

function modelPrompt(message, mode, route, workspace) {
  return `${buildSystemPrompt(mode, route, workspace)}\n\nUser request:\n${message.trim()}`;
}

// ─── SQL Chat helpers ─────────────────────────────────────────────────────────
function parseMessage(row) {
  return {
    id: row.id,
    role: row.role,
    content: row.content,
    mode: row.mode || undefined,
    modelId: row.model_id || undefined,
    modelName: row.model_name || undefined,
    routeReason: row.route_reason || undefined,
    live: row.live !== 0,
    artifacts: safeJson(row.artifacts_json, []),
    extractedFiles: safeJson(row.extracted_files_json, []),
    createdAt: row.created_at,
  };
}

function buildChatObject(db, session) {
  const msgs = db.prepare("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC").all(session.id);
  return {
    id: session.id,
    userId: session.user_id,
    title: session.title,
    pinned: Boolean(session.pinned),
    archived: Boolean(session.archived),
    unread: Boolean(session.unread),
    isActive: Boolean(session.is_active),
    messages: msgs.map(parseMessage),
    createdAt: session.created_at,
    updatedAt: session.updated_at,
  };
}

/**
 * Returns the most recently updated active chat session for a user, or undefined.
 * "Active" means is_active=1 — it is the current open thread.
 *
 * @param {DatabaseSync} db
 * @param {string} userId
 * @returns {object|undefined} chat_sessions row or undefined.
 */
function getActiveChatSession(db, userId) {
  return db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1").get(userId);
}

function getActiveChat(db, userId) {
  let session = getActiveChatSession(db, userId);
  if (!session) {
    const now = new Date().toISOString();
    const sessionId = uid("chat");
    db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)").run(sessionId, userId, "New Chat", now, now);
    db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_name, live, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)").run(
      uid("msg"), sessionId, "assistant", "Ready. Ask once, and Auto Router will choose the best approved model for your task.", "Olla Nest", now
    );
    session = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(sessionId);
  }
  return buildChatObject(db, session);
}

function archiveCurrentChat(db, userId) {
  const session = getActiveChatSession(db, userId);
  if (!session) return;
  const hasUserMsg = db.prepare("SELECT id FROM chat_messages WHERE session_id = ? AND role = 'user' LIMIT 1").get(session.id);
  if (!hasUserMsg) return;
  db.prepare("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?").run(new Date().toISOString(), session.id);
}

function chatFor(userId) {
  const { readDocs, writeDocs } = require("../models/model");
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

/**
 * Appends an event to the audit_events table.
 * Opens its own DB connection so it can be called from any context without
 * requiring the caller to pass a handle.  Errors are silently swallowed —
 * an audit write failure must never crash the main request.
 *
 * @param {string} actor - Human-readable name of the actor (usually user.name).
 * @param {string} action - Dot-separated action key (e.g. "chat.stream").
 * @param {string|null} detail - Short description of what happened.
 * @param {object} [extra={}] - Additional structured data stored as JSON.
 */
function appendAudit(actor, action, detail, extra = {}) {
  try {
    const db = openSql();
    try {
      db.prepare("INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(
        uid("audit"), actor, action, detail || "", JSON.stringify(extra), new Date().toISOString()
      );
    } finally { db.close(); }
  } catch {}
}

/**
 * Records a router_traces row for observability and debugging.
 * If a db handle is provided it is reused; otherwise a new connection is opened.
 * Errors are silently swallowed — trace failures must never break the chat flow.
 *
 * @param {{ userId, sessionId, message, mode, selectedModelId, tags, candidates, live }} trace
 * @param {DatabaseSync|null} [db] - Optional open handle (avoids extra open/close).
 */
function appendTrace(trace, db) {
  try {
    const run = (d) => d.prepare(
      "INSERT INTO router_traces (id, user_id, session_id, message, mode, selected_model_id, tags_json, candidates_json, live, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ).run(uid("trace"), trace.userId || null, trace.sessionId || null, trace.message || "", trace.mode || "",
      trace.selectedModelId || null, JSON.stringify(trace.tags || []), JSON.stringify(trace.candidates || []),
      trace.live ? 1 : 0, new Date().toISOString());
    if (db) { run(db); } else { const d = openSql(); try { run(d); } finally { d.close(); } }
  } catch {}
}

function autoTitle(messages) {
  const first = messages.find(m => m.role === "user");
  if (!first) return "New chat";
  return String(first.content).slice(0, 45).trim() + (first.content.length > 45 ? "…" : "");
}

module.exports = {
  estimateTokens,
  getModelContextWindow,
  buildContextMessages,
  buildSystemPrompt,
  modelPrompt,
  buildChatObject,
  parseMessage,
  getActiveChatSession,
  getActiveChat,
  archiveCurrentChat,
  chatFor,
  appendAudit,
  appendTrace,
  autoTitle,
};
