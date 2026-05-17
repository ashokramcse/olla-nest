/**
 * @file app.js
 * @description Olla Nest — main workspace SPA script (served at /app).
 *
 * Responsibilities:
 *   - Loads full application state from GET /api/state on boot and after each chat turn
 *   - Renders the sidebar (user info, allowed models, chat history, workspace config)
 *   - Handles SSE-based streaming chat via POST /api/chat/stream
 *   - Renders AI responses using marked.js (Markdown) + DOMPurify (XSS sanitisation)
 *   - Shows router decision panel (selected model, reason, candidate scores)
 *   - Manages file upload queue (images + text, up to 5 attachments)
 *   - Renders the Claude-style model picker dropdown with context window sizes
 *   - Handles password change, workspace folder save, and logout flows
 *
 * Global state:
 *   `state`   — the last full response from /api/state (models, user, chats, workspace, settings)
 *   `activeMode` — currently selected chat mode (ask|build|review|fix|learn|debug|test|docs|plan)
 *   `uploadedFiles` — files queued for the next message [{name, type, data}]
 *
 * Key flows:
 *   Page load → loadState() → renderSidebar() + renderMessages()
 *   User submits → chatForm submit → SSE stream → token events → renderMarkdown()
 *     → done event → loadState() (refresh sidebar + chat history)
 */

let state = null;
let activeMode = "ask";
let accountOpen = false;
let workspaceConfigOpen = false;

/** Shorthand: getElementById */
const $ = (id) => document.getElementById(id);
/** Shorthand: querySelector */
const $q = (sel) => document.querySelector(sel);
/** Shorthand: querySelectorAll → Array */
const $all = (sel) => Array.from(document.querySelectorAll(sel));

/**
 * HTML-escapes a value for safe insertion into innerHTML.
 * Converts the five dangerous HTML characters to their entity equivalents.
 * Always call this before inserting any server-provided or user-provided string into HTML.
 *
 * @param {*} v - Value to escape (non-strings are coerced via String()).
 * @returns {string} Safe HTML string.
 */
function esc(v) {
  return String(v ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

/**
 * Wrapper around fetch() for all JSON API calls.
 * Always adds Content-Type and X-Requested-With headers (the latter satisfies
 * the server's CSRF guard on state-changing requests).
 * Redirects to /login on 401 so all callers can assume the user is authenticated.
 *
 * @param {string} path - API path (e.g. "/api/state").
 * @param {RequestInit} [opts={}] - fetch() options (method, body, etc.).
 * @returns {Promise<object|null>} Parsed JSON response, or null on 401.
 * @throws {Error} On non-2xx responses (message from data.error).
 */
async function api(path, opts = {}) {
  const headers = { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", ...(opts.headers || {}) };
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401) { window.location.href = "/login"; return null; }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

/**
 * Generates 1–2 uppercase initials from a display name.
 * "Jane Smith" → "JS", "Alice" → "A", undefined → "?"
 *
 * @param {string} name - Display name.
 * @returns {string} 1–2 character initials string.
 */
function initials(name) {
  return (name || "?").split(" ").map(w => w[0]).join("").slice(0, 2).toUpperCase();
}

/**
 * Filters the global state.models list to the models the current user is
 * allowed to use.  Also hides API models when allowApiModels is false globally.
 *
 * @returns {object[]} Array of allowed model objects from state.
 */
function allowedModels() {
  if (!state) return [];
  return state.models.filter(m =>
    state.allowedModelIds.includes(m.id) &&
    m.status !== "disabled" &&
    !(m.provider === "api" && !state.settings.allowApiModels)
  );
}

/**
 * Returns a human-friendly relative time string ("3m ago", "2h ago", "1d ago").
 * Used in the sidebar chat history and message footers.
 *
 * @param {string|null} iso - ISO 8601 timestamp string.
 * @returns {string} Relative time label or "" if iso is falsy.
 */
function timeAgo(iso) {
  if (!iso) return "";
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

/**
 * Re-renders the entire sidebar from the current global `state`.
 * Called after every loadState() — covers user info, department, model list,
 * model picker, token usage pill, workspace path, access policy card,
 * terminal FAB visibility, and the chat history list.
 */
function renderSidebar() {
  const u = state.activeUser;
  $("userAvatar").textContent = initials(u.name);
  $("userName").textContent = u.name;
  const dept = state.departments.find(d => d.id === u.departmentId);
  $("userMeta").textContent = `${u.role} · ${dept?.name || "Workspace"}`;
  $("adminLink").style.display = u.role === "admin" ? "flex" : "none";

  // Welcome bar
  // If name looks like an email (old autofill bug), use the part before @
  const rawName = u.name || u.email || "";
  const displayName = rawName.includes("@") ? rawName.split("@")[0] : rawName;
  const firstName = displayName.split(" ")[0];
  $("topbarTitle").textContent = `Welcome, ${firstName}`;
  $("topbarSub").textContent = `${dept?.name || "General"} · Auto Router active`;
  const statWs = document.getElementById("statWorkspace");
  if (statWs) { const ws2 = state.workspace; statWs.textContent = ws2?.workspaceRoot ? (ws2.workspaceRoot.split("/").pop() || "set") : "not set"; }
  const infoDept = document.getElementById("infoDept");
  if (infoDept) infoDept.textContent = dept?.name || "—";
  const roleMini = document.querySelector(".role-chip");
  if (roleMini) roleMini.textContent = u.role.charAt(0).toUpperCase() + u.role.slice(1);

  // Models
  const models = allowedModels();
  $("sidebarModels").innerHTML = models.length
    ? models.map(m => `<div class="model-item"><div class="model-dot"></div><div class="model-name" title="${esc(m.name)}">${esc(m.name)}</div></div>`).join("")
    : `<div class="model-item"><div class="model-dot" style="background:var(--muted)"></div><div class="model-name" style="color:var(--muted)">No approved models</div></div>`;

  // Repopulate the Claude-style picker dropdown (selectedModelId tracks choice)
  populateAppModelPicker(models);

  // Model connected status pill
  const statModelPill = document.getElementById("statModelPill");
  const statModelName = document.getElementById("statModelName");
  if (statModelPill && statModelName) {
    const activeM = models.find(m => m.status === "available") || models[0];
    if (activeM) {
      statModelName.textContent = activeM.name;
      statModelPill.style.display = "flex";
    } else {
      statModelPill.style.display = "none";
    }
  }

  // Token usage pill
  loadTokenUsage();

  // Terminal FAB — only show for users with workspace:build or admin
  const termFab = document.getElementById("termToggle");
  if (termFab) {
    const canTerminal = u.role === "admin" || (u.rights || []).includes("workspace:build");
    termFab.style.display = canTerminal ? "flex" : "none";
  }

  // Update model ring infographic
  if (typeof updateModelRing === "function") updateModelRing(models.length, 10);
  const capCard = document.getElementById("capabilityCard");
  if (capCard) capCard.style.display = models.length > 0 ? "block" : "none";

  // Workspace
  const ws = state.workspace;
  if (ws) {
    const wsFullPath = ws.outputFolder || ws.workspaceRoot || "";
    const wsEl = $("workspacePath");
    if (wsFullPath) {
      wsEl.textContent = wsFullPath;
      wsEl.title = wsFullPath;
    } else {
      wsEl.textContent = "Not configured";
    }
    const modeLabels = { default: "Approve writes", review: "Auto-review", full: "Full access" };
    $("workspaceModeTag").innerHTML = `<span class="ws-mode-tag">${modeLabels[ws.permissionMode] || ws.permissionMode}</span>`;
    $("workspaceFolderInput").value = ws.workspaceRoot || "";
    $("permissionModeSelect").value = ws.permissionMode || "default";
  }

  // Access policy (profile drawer)
  const rights = u.rights || [];
  $("accessPolicy").innerHTML = `
    <strong>${u.email || ""}</strong><br>
    ${dept?.name || "No department"} · ${u.role}<br><br>
    <span style="font-size:11px;">Rights: ${rights.map(r => `<span class="badge badge-indigo" style="margin:1px 2px;">${esc(r)}</span>`).join(" ")}</span><br><br>
    <span style="font-size:12px;">${models.length} model${models.length !== 1 ? "s" : ""} approved — access comes from your user, group, and department grants.</span>
  `;

  // Sidebar "My Access" card — show rights as pills
  const accessRightsEl = document.getElementById("sidebarAccessRights");
  if (accessRightsEl) {
    const rightLabels = {
      "chat:use": "Chat",
      "models:local:use": "Local Models",
      "models:coding:use": "Coding Models",
      "models:reasoning:use": "Reasoning Models",
      "models:external:use": "External Models",
      "workspace:build": "Terminal",
      "files:upload": "File Upload",
      "tools:call": "Tools",
      "api:use": "API Access",
      "admin:manage": "Admin",
      "users:manage": "User Mgmt",
      "models:manage": "Model Mgmt",
      "audit:read": "Audit Logs",
    };
    accessRightsEl.innerHTML = rights.map(r =>
      `<span class="right-badge">${esc(rightLabels[r] || r)}</span>`
    ).join("") || `<span style="font-size:12px;color:var(--mute);">No rights configured</span>`;
  }

  // Terminal note in access card
  const termNote = document.getElementById("sidebarTerminalNote");
  const canTerminal = u.role === "admin" || rights.includes("workspace:build");
  if (termNote) termNote.style.display = canTerminal ? "block" : "none";

  // Role chip in sidebar profile card
  const roleChip = document.getElementById("sidebarRoleChip");
  if (roleChip) roleChip.textContent = u.role.charAt(0).toUpperCase() + u.role.slice(1);

  // Sidebar chat history
  renderSidebarChats();
}

/**
 * Renders the compact chat history list in the sidebar.
 * Shows title + relative time for each session in state.chats.
 * The first chat (most recent active) is highlighted.
 */
function renderSidebarChats() {
  const el = document.getElementById("sidebarChats");
  if (!el) return;
  const chats = state.chats || [];
  if (!chats.length) {
    el.innerHTML = `<div style="font-size:12px;color:var(--mute);padding:4px 0;">No conversations yet</div>`;
    return;
  }
  el.innerHTML = chats.map(c => {
    const title = esc(c.title || "New Chat");
    const ago = timeAgo(c.updatedAt || c.createdAt);
    const isActive = c.id === (state.chats?.[0]?.id);
    const pinned = c.pinned ? " pinned" : "";
    return `<div class="sidebar-chat-item${isActive ? " active" : ""}${pinned}" data-chat-id="${esc(c.id)}" title="${title}">
      <div class="chat-pin-dot"></div>
      <div class="chat-item-body">
        <div class="chat-item-title">${title}</div>
        <div class="sidebar-chat-time">${ago}</div>
      </div>
      <button class="chat-item-menu-btn" data-chat-id="${esc(c.id)}" title="Options">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
      </button>
    </div>`;
  }).join("");
}

// ─── Chat context menu ────────────────────────────────────────────────────────

let _chatCtxMenu = null;

/** Close and remove the floating context menu if open */
function closeChatCtxMenu() {
  if (_chatCtxMenu) { _chatCtxMenu.remove(); _chatCtxMenu = null; }
}

/**
 * Open the three-dot context menu for a chat item.
 * Positions itself relative to the trigger button, flips upward near the bottom of the viewport.
 * @param {string} chatId - The chat_sessions.id
 * @param {HTMLElement} trigger - The ⋮ button element
 */
function openChatCtxMenu(chatId, trigger) {
  closeChatCtxMenu();
  const chat = (state.chats || []).find(c => c.id === chatId);
  if (!chat) return;
  const isPinned = chat.pinned;

  const menu = document.createElement("div");
  menu.className = "chat-ctx-menu";
  menu.innerHTML = `
    <button class="chat-ctx-item" data-action="rename">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
      Rename
    </button>
    <button class="chat-ctx-item" data-action="pin">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
      ${isPinned ? "Unpin" : "Pin"}
    </button>
    <div class="chat-ctx-sep"></div>
    <button class="chat-ctx-item danger" data-action="delete">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>
      Delete
    </button>
  `;

  // Position: below trigger, flip up if too close to bottom
  document.body.appendChild(menu);
  _chatCtxMenu = menu;
  const rect = trigger.getBoundingClientRect();
  const menuH = 130;
  const top = (window.innerHeight - rect.bottom < menuH) ? rect.top - menuH : rect.bottom + 4;
  menu.style.top = top + "px";
  menu.style.left = Math.max(4, rect.left - 120) + "px";

  menu.addEventListener("click", async (e) => {
    const btn = e.target.closest("[data-action]");
    if (!btn) return;
    closeChatCtxMenu();
    const action = btn.dataset.action;

    if (action === "rename") {
      // Inline rename: replace the title text with an input field directly in the sidebar
      const itemEl = document.querySelector(`.sidebar-chat-item[data-chat-id="${chatId}"]`);
      const titleEl = itemEl?.querySelector(".chat-item-title");
      if (!titleEl) return;
      const current = chat.title || "New Chat";
      const inp = document.createElement("input");
      inp.value = current;
      inp.style.cssText = "width:100%;font-size:13px;font-family:inherit;border:1px solid var(--yellow-deep);border-radius:6px;padding:2px 6px;outline:none;background:#fff;";
      titleEl.replaceWith(inp);
      inp.focus(); inp.select();
      const commit = async () => {
        const val = inp.value.trim();
        const span = document.createElement("div");
        span.className = "chat-item-title";
        span.textContent = val || current;
        inp.replaceWith(span);
        if (val && val !== current) {
          await api(`/api/threads/${chatId}`, { method: "PATCH", body: JSON.stringify({ title: val }) });
          await loadState();
        }
      };
      inp.addEventListener("blur", commit);
      inp.addEventListener("keydown", e => { if (e.key === "Enter") inp.blur(); if (e.key === "Escape") { inp.value = current; inp.blur(); } });
    }

    if (action === "pin") {
      await api(`/api/threads/${chatId}`, { method: "PATCH", body: JSON.stringify({ pinned: !isPinned }) });
      await loadState();
    }

    if (action === "delete") {
      showConfirm("Delete this chat? This cannot be undone.", async () => {
        await api(`/api/threads/${chatId}`, { method: "DELETE" });
        await loadState();
      });
    }
  });
}

// Close menu when clicking outside
document.addEventListener("click", (e) => {
  if (_chatCtxMenu && !_chatCtxMenu.contains(e.target)) closeChatCtxMenu();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") closeChatCtxMenu();
});

/**
 * Converts a Markdown string to safe HTML for display in the chat UI.
 *
 * Uses marked.js with a custom renderer that adds:
 *   - Language label on code fences
 *   - "Copy" button on every code block
 *   - "Run in terminal" button on single-line shell-like commands
 *
 * Output is sanitised with DOMPurify (allowing onclick attributes so the
 * Copy/Run buttons work) to prevent XSS from AI-generated HTML.
 * Falls back to a plain <pre> if marked.js is not loaded.
 *
 * @param {string} content - Raw Markdown/text from the model.
 * @returns {string} Safe HTML string.
 */
function renderMarkdown(content) {
  if (typeof marked === "undefined") return `<pre style="white-space:pre-wrap;">${esc(content)}</pre>`;
  marked.setOptions({ breaks: true, gfm: true });
  // Use marked with a custom renderer for code blocks to add copy button
  const renderer = new marked.Renderer();
  renderer.code = ({ text, lang }) => {
    const escaped = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const label = lang ? `<span class="code-lang-label">${esc(lang)}</span>` : "";
    // Show "Run in terminal" for shell-like single-line commands
    const isRunnable = ["bash","sh","shell","zsh","fish","console","terminal","cmd","powershell"].includes((lang||"").toLowerCase())
      || (!lang && /^[a-z][\w\/\-]/.test(text.trim()) && !text.includes("\n") && text.trim().length < 200);
    const runBtn = isRunnable
      ? `<button class="run-in-term-btn" onclick="runInTerminal(this)" title="Run in terminal"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="5 3 19 12 5 21 5 3"/></svg> Run</button>`
      : "";
    return `<div class="md-code-block">${label}<button class="md-copy-btn" onclick="copyCode(this)">Copy</button><pre><code>${escaped}</code></pre>${runBtn}</div>`;
  };
  const raw = marked.parse(content, { renderer });
  // Sanitize with DOMPurify to prevent XSS from AI-generated HTML
  return typeof DOMPurify !== "undefined"
    ? DOMPurify.sanitize(raw, { ADD_ATTR: ["onclick"], FORCE_BODY: false })
    : raw;
}

/**
 * Copies the code inside the nearest code block to the clipboard.
 * Provides brief "Copied!" feedback on the button, then resets after 2s.
 *
 * @param {HTMLButtonElement} btn - The "Copy" button element inside the code block.
 */
function copyCode(btn) {
  const code = btn.parentElement.querySelector("code").innerText;
  navigator.clipboard.writeText(code).then(() => {
    btn.textContent = "Copied!";
    setTimeout(() => { btn.textContent = "Copy"; }, 2000);
  });
}

/**
 * Sends a code block's content to the embedded terminal via the global
 * window.termSendCommand() function (set up by the terminal panel script).
 * Shows an alert if the terminal is not available (no workspace:build right).
 *
 * @param {HTMLButtonElement} btn - The "Run" button inside the code block.
 */
function runInTerminal(btn) {
  const code = btn.closest(".md-code-block").querySelector("code").innerText.trim();
  if (!code) return;
  if (typeof window.termSendCommand === "function") {
    btn.textContent = "▶ Sent!";
    btn.style.background = "#2a3a1a";
    btn.style.borderColor = "#86efac55";
    btn.style.color = "#86efac";
    setTimeout(() => {
      btn.innerHTML = `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="5 3 19 12 5 21 5 3"/></svg> Run`;
      btn.style.cssText = "";
    }, 2000);
    window.termSendCommand(code);
  } else {
    alert("Terminal not available. Make sure you have workspace:build permission.");
  }
}

/**
 * Re-renders the entire messages list from state.chats.
 * User bubbles show plain text; assistant bubbles use renderMarkdown().
 * Each assistant message footer shows: model name tag, saved artifact chips.
 * Auto-scrolls to the bottom after rendering.
 */
function renderMessages() {
  const chat = state.chats?.find(c => c.userId === state.activeUser.id) || state.chats?.[0];
  const msgs = chat?.messages || [];
  if (!msgs.length) {
    $("messages").innerHTML = `<div style="text-align:center; padding:40px 20px; color:var(--muted);">
      <div style="font-size:32px; margin-bottom:12px;">✦</div>
      <div style="font-size:15px; font-weight:600; color:var(--ink); margin-bottom:6px;">Ready when you are</div>
      <div style="font-size:13px;">Ask anything · Build · Review · Fix · Learn</div>
    </div>`;
    return;
  }

  $("messages").innerHTML = msgs.map(msg => {
    const isUser = msg.role === "user";
    const meta = isUser
      ? `${esc(state.activeUser.name)}${msg.mode ? ` · <span class="badge badge-default" style="font-size:10px;">${esc(msg.mode)}</span>` : ""}`
      : `${esc(msg.modelName || "Olla Nest")}${msg.live === false ? ` · <span class="badge badge-amber" style="font-size:10px;">setup needed</span>` : ""}`;

    const footer = !isUser ? `
      <div class="message-footer">
        ${msg.modelName ? `<span class="message-model-tag"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="3"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/></svg>${esc(msg.modelName)}</span>` : ""}
        ${(msg.artifacts || []).map(a => `<span class="artifact-chip"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>${esc(a.relativePath || a.name)}</span>`).join("")}
      </div>` : "";

    const bubbleContent = isUser
      ? `<div class="message-bubble user-bubble">${esc(msg.content)}</div>`
      : `<div class="message-bubble assistant-bubble md-body">${renderMarkdown(msg.content)}</div>`;

    return `<div class="message-wrap ${msg.role}">
      <div class="message-meta">${meta}</div>
      ${bubbleContent}
      ${footer}
    </div>`;
  }).join("");

  $("messages").scrollTop = $("messages").scrollHeight;
}

/**
 * Renders the Auto Router panel (right sidebar) showing which model was chosen,
 * the routing reason, capability tags, and the top candidate scores.
 * Call with null to show the default empty-state prompt.
 *
 * @param {{ selected: object, reason: string, tags: string[], candidates: object[] }|null} route
 */
function renderRouter(route) {
  if (!route || !route.selected) {
    $("routerContent").innerHTML = `<div class="router-empty">Send a request to see how Auto Router selects the best model for your task.</div>`;
    return;
  }
  const top3 = (route.candidates || []).slice(0, 5);
  $("routerContent").innerHTML = `
    <div class="router-body">
      <div class="router-model">${esc(route.selected.name)}</div>
      <div class="router-reason">${esc(route.reason)}</div>
      ${route.tags?.length ? `<div class="router-tags">${route.tags.map(t => `<span class="router-tag">${esc(t)}</span>`).join("")}</div>` : ""}
      ${top3.length > 1 ? `
        <div class="router-candidates">
          <div class="router-cand-label">All candidates</div>
          ${top3.map(c => `<div class="router-cand-item">
            <span class="router-cand-name">${esc(c.name)}</span>
            <span class="router-cand-score">${c.score}</span>
          </div>`).join("")}
        </div>` : ""}
    </div>`;
}

/**
 * Fetches full application state from GET /api/state and re-renders the UI.
 * Sets window.state so the terminal panel and other scripts can access it.
 * Also dispatches a "olla-state-updated" CustomEvent with the new state for
 * any listeners that need to react (e.g. terminal workspace path sync).
 */
async function loadState() {
  state = await api("/api/state");
  if (!state) return;
  window.state = state;
  renderSidebar();
  renderMessages();
  // Notify terminal panel about workspace path
  window.dispatchEvent(new CustomEvent("olla-state-updated", { detail: state }));
}

/**
 * Fetches today's and this month's token usage from GET /api/account/usage
 * and updates the token usage pill in the sidebar.
 * The bar turns orange at 70% and red at 90% of the daily limit.
 */
async function loadTokenUsage() {
  const pill = document.getElementById("statTokenPill");
  const used = document.getElementById("statTokenUsed");
  const limit = document.getElementById("statTokenLimit");
  const bar = document.getElementById("statTokenBar");
  if (!pill || !used || !limit || !bar) return;
  try {
    const data = await api("/api/account/usage");
    if (!data) return;
    const pct = Math.min(100, Math.round((data.tokensUsedToday / data.dailyTokenLimit) * 100));
    used.textContent = data.tokensUsedToday.toLocaleString();
    limit.textContent = data.dailyTokenLimit.toLocaleString();
    bar.style.width = pct + "%";
    bar.className = "token-bar-fill" + (pct >= 90 ? " over" : pct >= 70 ? " warn" : "");
    pill.style.display = "flex";
  } catch {}
}

/**
 * Polls GET /api/ollama/models to check whether Ollama is reachable and updates
 * the status dot + label in the sidebar header.
 */
async function checkOllama() {
  const label = $("ollamaStatus");
  const dot = $("ollamaStatusDot");
  try {
    const data = await api("/api/ollama/models");
    if (!data) return;
    if (data.ok) {
      if (label) label.textContent = "Ollama connected";
      if (dot) { dot.className = "status-dot ok"; }
    } else {
      if (label) label.textContent = "Ollama not connected";
      if (dot) { dot.className = "status-dot off"; }
    }
  } catch {
    if (label) label.textContent = "Ollama not connected";
    if (dot) { dot.className = "status-dot off"; }
  }
}

/**
 * Shows or hides the "Save to workspace" toggle based on whether the user has
 * a workspace configured.  Auto-checks the toggle if permissionMode is "full"
 * (meaning writes are always approved without the user toggling each time).
 */
function updateWriteToggle() {
  const label = $("writeToggleLabel");
  const ws = state?.workspace;
  const hasWorkspace = ws?.workspaceRoot;
  label.style.display = hasWorkspace ? "flex" : "none";
  if (ws?.permissionMode === "full") {
    $("writeToWorkspace").checked = true;
    label.classList.add("enabled");
  }
  const folderName = hasWorkspace ? ws.workspaceRoot.split("/").filter(Boolean).pop() : null;
  const labelText = label.querySelector("span.write-label") || label;
  if (folderName && label.querySelector("span.write-label")) {
    label.querySelector("span.write-label").textContent = `Save to ${folderName}`;
  }
}

// Events

$("writeToWorkspace").addEventListener("change", (e) => {
  $("writeToggleLabel").classList.toggle("enabled", e.target.checked);
});

/**
 * Archives the current chat thread and starts a fresh one.
 * POSTs to /api/chat/clear then reloads state so the sidebar and message
 * panel both reflect the new empty session.
 */
async function startNewChat() {
  await api("/api/chat/clear", { method: "POST", body: "{}" });
  renderRouter(null);
  await loadState();
}
$("newChatBtn").addEventListener("click", startNewChat);

// Sidebar new chat button
const sideNewChatBtn = document.getElementById("newChatSideBtn");
if (sideNewChatBtn) sideNewChatBtn.addEventListener("click", startNewChat);

// Sidebar chat item click — delegate (handles both ⋮ menu button and chat item switch)
document.getElementById("sidebarChats")?.addEventListener("click", async (e) => {
  // Three-dot menu button
  const menuBtn = e.target.closest(".chat-item-menu-btn");
  if (menuBtn) {
    e.stopPropagation();
    openChatCtxMenu(menuBtn.dataset.chatId, menuBtn);
    return;
  }
  // Chat item row — switch to that session
  const item = e.target.closest(".sidebar-chat-item");
  if (!item) return;
  const chatId = item.dataset.chatId;
  if (!chatId) return;
  closeChatCtxMenu();
  // Switch active session on server then reload state
  await api(`/api/threads/${chatId}/activate`, { method: "POST" }).catch(() => {});
  await loadState();
  document.getElementById("messages")?.scrollTo({ top: 0, behavior: "smooth" });
});

let activeStreamReader = null;
let streamingSessionId = null;
let streamingMessageId = null;

/**
 * Submits a thumbs-up (1) or thumbs-down (-1) rating for an assistant message.
 * Called from the feedback buttons injected into assistant message wrappers after streaming.
 * Marks the clicked button with "voted" CSS class for visual confirmation.
 *
 * @param {HTMLButtonElement} btn - The thumb button that was clicked.
 * @param {string|null} messageId - The persisted chat_messages.id (null if stream failed).
 * @param {string} sessionId - The chat_sessions.id for the current thread.
 * @param {1|-1} rating - Thumbs up (1) or thumbs down (-1).
 */
function submitFeedback(btn, messageId, sessionId, rating) {
  if (!messageId) return; // no persisted message (e.g. DB was closed during stream)
  fetch("/api/feedback", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" },
    body: JSON.stringify({ messageId, sessionId, rating, comment: "" }),
  }).then(r => r.json()).then(() => {
    btn.closest(".feedback-row").querySelectorAll(".thumb-btn").forEach(b => b.classList.remove("voted"));
    btn.classList.add("voted");
  }).catch(() => {});
}

/**
 * Chat form submit handler — the main user interaction entry point.
 *
 * Flow:
 *  1. Disable send button, show stop button, inject optimistic user + assistant bubbles.
 *  2. Append any attached text files to the message body as fenced code blocks.
 *  3. POST to /api/chat/stream with message, mode, model override, images, writeToWorkspace flag.
 *  4. Read SSE stream:
 *     - "routing" event: update router panel + assistant bubble model label.
 *     - "token" event: append token to fullContent, re-render Markdown live.
 *     - "done" event: finalise bubble, inject feedback buttons, reload state.
 *     - "error" event: show error in assistant bubble.
 *  5. On AbortError (stop button), silently end — partial content stays visible.
 *  6. Re-enable send button in finally block.
 */
$("chatForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = $("messageInput");
  const message = input.value.trim();
  if (!message) return;

  const sendBtn = $("sendBtn");
  const stopBtn = $("stopBtn");
  sendBtn.disabled = true;
  sendBtn.textContent = "Sending…";
  stopBtn.style.display = "inline-flex";
  input.value = "";

  $("routerContent").innerHTML = `<div class="router-body" style="display:flex;align-items:center;gap:8px;color:var(--muted);font-size:13px;">
    <div style="width:14px;height:14px;border:2px solid var(--border);border-top-color:var(--primary);border-radius:50%;animation:spin 0.7s linear infinite;"></div>
    Routing…
  </div>
  <style>@keyframes spin{to{transform:rotate(360deg)}}</style>`;

  // Optimistically append user message to chat
  const msgs = $("messages");
  const userBubbleId = "streaming-user-" + Date.now();
  const asstBubbleId = "streaming-asst-" + Date.now();
  // Show image thumbnails in user bubble (before files are cleared)
  const imgPreviews = uploadedFiles.filter(f => f.type.startsWith("image/"))
    .map(f => `<img src="data:${f.type};base64,${f.data}" style="max-height:80px;max-width:120px;border-radius:8px;margin-top:6px;display:block;">`).join("");
  const textPreviews = uploadedFiles.filter(f => !f.type.startsWith("image/"))
    .map(f => `<div style="font-size:11px;color:var(--mute);margin-top:4px;">📎 ${esc(f.name)}</div>`).join("");

  msgs.insertAdjacentHTML("beforeend", `
    <div class="message-wrap user" id="${userBubbleId}">
      <div class="message-meta">${esc(state?.activeUser?.name || "You")}</div>
      <div class="message-bubble user-bubble">${esc(message)}${imgPreviews}${textPreviews}</div>
    </div>
    <div class="message-wrap assistant" id="${asstBubbleId}">
      <div class="message-meta">Olla Nest</div>
      <div class="message-bubble assistant-bubble md-body" id="${asstBubbleId}-content"><span class="streaming-cursor"></span></div>
    </div>
  `);
  msgs.scrollTop = msgs.scrollHeight;

  let fullContent = "";
  let currentRoute = null;

  try {
    // Collect images (base64) from uploaded files
    const imageFiles = uploadedFiles.filter(f => f.type.startsWith("image/")).map(f => f.data);
    const textFiles = uploadedFiles.filter(f => !f.type.startsWith("image/"));
    // Append text file contents to message
    let fullMessage = message;
    if (textFiles.length) {
      fullMessage += "\n\n" + textFiles.map(f => `\`\`\`${f.name}\n${f.data}\n\`\`\``).join("\n\n");
    }

    // Clear uploaded files after sending
    uploadedFiles.length = 0;
    renderFilePreviews();

    const response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" },
      body: JSON.stringify({
        message: fullMessage,
        mode: activeMode,
        manualModelId: selectedModelId || null,
        writeToWorkspace: $("writeToWorkspace")?.checked || false,
        images: imageFiles.length ? imageFiles : undefined,
      }),
    });

    if (!response.ok) {
      const errData = await response.json().catch(() => ({ error: "Stream failed" }));
      throw new Error(errData.error || "Stream failed");
    }

    const reader = response.body.getReader();
    activeStreamReader = reader;
    const decoder = new TextDecoder();
    let buf = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const parts = buf.split("\n\n");
      buf = parts.pop();
      for (const part of parts) {
        if (!part.startsWith("data: ")) continue;
        try {
          const event = JSON.parse(part.slice(6));
          if (event.type === "routing") {
            $("routerContent").innerHTML = `<div class="router-body">
              <div class="router-model">${esc(event.model)}</div>
              <div class="router-reason">${esc(event.reason)}</div>
            </div>`;
            const meta = $(`${asstBubbleId}`);
            if (meta) meta.querySelector(".message-meta").textContent = esc(event.model);
          } else if (event.type === "token") {
            fullContent += event.content;
            const bubble = $(`${asstBubbleId}-content`);
            if (bubble) bubble.innerHTML = renderMarkdown(fullContent) + '<span class="streaming-cursor"></span>';
            msgs.scrollTop = msgs.scrollHeight;
          } else if (event.type === "done") {
            const bubble = $(`${asstBubbleId}-content`);
            if (bubble) bubble.innerHTML = renderMarkdown(fullContent);
            // Add feedback buttons
            streamingMessageId = event.messageId || null;
            streamingSessionId = state?.chats?.[0]?.id || null;
            const asstWrap = $(`${asstBubbleId}`);
            if (asstWrap) {
              const feedbackBtns = event.messageId
                ? `<button class="thumb-btn" onclick="submitFeedback(this,'${event.messageId}','${streamingSessionId || ''}',1)">👍</button>
                   <button class="thumb-btn" onclick="submitFeedback(this,'${event.messageId}','${streamingSessionId || ''}', -1)">👎</button>`
                : "";
              asstWrap.insertAdjacentHTML("beforeend", `
                <div class="feedback-row">
                  ${feedbackBtns}
                  <span style="font-size:11px;color:var(--muted);margin-left:4px;">${event.tokensUsed ? event.tokensUsed + ' tokens' : ''}</span>
                </div>`);
            }
            await loadState();
          } else if (event.type === "error") {
            const bubble = $(`${asstBubbleId}-content`);
            if (bubble) bubble.innerHTML = `<span style="color:var(--danger);">${esc(event.message)}</span>`;
          }
        } catch {}
      }
    }
  } catch (err) {
    if (err.name !== "AbortError") {
      const bubble = $(`${asstBubbleId}-content`);
      if (bubble) bubble.innerHTML = `<span style="color:var(--danger);">${esc(err.message)}</span>`;
      $("routerContent").innerHTML = `<div class="router-body"><div style="font-size:13px;color:var(--danger);">${esc(err.message)}</div></div>`;
    }
  } finally {
    activeStreamReader = null;
    sendBtn.disabled = false;
    sendBtn.textContent = "Send";
    stopBtn.style.display = "none";
  }
});

$("stopBtn").addEventListener("click", () => {
  if (activeStreamReader) { activeStreamReader.cancel(); }
});

// Account panel toggle
$("accountBtn").addEventListener("click", () => {
  accountOpen = !accountOpen;
  $("accountPanel").style.display = accountOpen ? "block" : "none";
  $("accountBtn").textContent = accountOpen ? "Close" : "Account";
});

$("passwordForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = $("passwordMsg");
  msg.className = "form-message";
  msg.textContent = "";
  try {
    await api("/api/account/password", {
      method: "POST",
      body: JSON.stringify({
        currentPassword: $("currentPassword").value,
        newPassword: $("newPassword").value,
      }),
    });
    $("currentPassword").value = "";
    $("newPassword").value = "";
    msg.className = "form-message success";
    msg.textContent = "Password updated successfully.";
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
});

// Workspace config
$("configWorkspaceBtn").addEventListener("click", () => {
  workspaceConfigOpen = !workspaceConfigOpen;
  $("workspaceConfigPanel").style.display = workspaceConfigOpen ? "block" : "none";
  $("configWorkspaceBtn").textContent = workspaceConfigOpen ? "Cancel" : "Configure folder";
});

$("cancelWorkspaceBtn").addEventListener("click", () => {
  workspaceConfigOpen = false;
  $("workspaceConfigPanel").style.display = "none";
  $("configWorkspaceBtn").textContent = "Configure folder";
});

$("saveWorkspaceBtn").addEventListener("click", async () => {
  const msg = $("workspaceSaveMsg");
  msg.textContent = "";
  const path = $("workspaceFolderInput").value.trim();
  if (!path) { msg.textContent = "Folder path required."; return; }
  try {
    await api("/api/workspace/local-settings", {
      method: "POST",
      body: JSON.stringify({
        workspaceRoot: path,
        permissionMode: $("permissionModeSelect").value,
      }),
    });
    msg.textContent = "Saved!";
    await loadState();
    setTimeout(() => {
      workspaceConfigOpen = false;
      $("workspaceConfigPanel").style.display = "none";
      $("configWorkspaceBtn").textContent = "Configure folder";
      msg.textContent = "";
    }, 1200);
  } catch (err) {
    msg.textContent = err.message;
    msg.style.color = "#EF4444";
  }
});

$("logoutBtn").addEventListener("click", async () => {
  await api("/api/auth/logout", { method: "POST", body: "{}" });
  window.location.href = "/login";
});

// Textarea auto-resize + Enter to send
$("messageInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    $("chatForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  }
});

// ── Claude-style app model picker ───────────────────────────────────────────

/**
 * Looks up the real context window size for a model by name from global state.
 * Falls back to 8192 if the model is not found or has no contextSize stored.
 * Used to display the context window next to each model in the picker dropdown.
 *
 * @param {string} name - Model name (display name or model_ref).
 * @returns {number} Context window size in tokens.
 */
function estimateCtxApp(name) {
  // Use the real context_size stored from Ollama /api/show — no hardcoded guessing
  const m = (state?.models || []).find(m => m.name === name || m.model === name);
  return m?.contextSize || 8192;
}
function fmtCtx(n) { return n >= 1000 ? (n / 1000).toFixed(0) + "k" : String(n); }

/** Tracks the manually selected model ID — empty string means Auto Router */
let selectedModelId = "";

/**
 * Populates the model picker dropdown with "Auto Router" + one item per allowed model.
 * Clicking an item sets selectedModelId and updates the trigger label.
 *
 * @param {object[]} models - Array of allowed model objects from state.
 */
function populateAppModelPicker(models) {
  const list = document.getElementById("appModelList");
  if (!list) return;
  const isAuto = !selectedModelId;
  let html = `<div class="app-auto-item${isAuto ? " active" : ""}" data-id="" data-name="Auto Router">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    <span>Auto Router</span>${isAuto ? `<span class="app-model-check">✓</span>` : ""}
  </div>`;
  (models || []).forEach(m => {
    const ctx = estimateCtxApp(m.name);
    const active = m.id === selectedModelId;
    html += `<div class="app-model-item${active ? " active" : ""}" data-id="${esc(m.id)}" data-name="${esc(m.name)}">
      <div class="app-model-dot${m.status !== "available" ? " off" : ""}"></div>
      <span>${esc(m.name)}</span>
      <span class="app-model-ctx">${fmtCtx(ctx)}</span>
      ${active ? `<span class="app-model-check">✓</span>` : ""}
    </div>`;
  });
  list.innerHTML = html;
  list.querySelectorAll("[data-id]").forEach(el => {
    el.addEventListener("click", () => {
      selectedModelId = el.dataset.id;
      const nameEl = document.getElementById("appModelName");
      if (nameEl) nameEl.textContent = el.dataset.name || "Auto Router";
      closeAppModelDropdown();
    });
  });
}

const appPicker = document.getElementById("appModelPicker");
const appDropdown = document.getElementById("appModelDropdown");

/**
 * Opens the model picker dropdown, positioning it above or below the trigger
 * button depending on available screen space (prevents clipping at viewport edges).
 */
function openAppModelDropdown() {
  if (!appDropdown) return;
  const models = (state?.models || []).filter(m => (state?.approvedModels || []).some(a => a.id === m.id) || m.status === "available");
  populateAppModelPicker(models.length ? models : (state?.models || []).filter(m => m.status === "available"));
  // Position fixed relative to trigger rect (escapes overflow:hidden parents)
  const rect = appPicker.getBoundingClientRect();
  const dropH = 320; // estimated max height
  const spaceBelow = window.innerHeight - rect.bottom;
  if (spaceBelow < dropH && rect.top > dropH) {
    appDropdown.style.top = (rect.top - dropH - 4) + "px";
  } else {
    appDropdown.style.top = (rect.bottom + 4) + "px";
  }
  appDropdown.style.left = rect.left + "px";
  appPicker?.classList.add("open");
  appDropdown.classList.add("open");
}
function closeAppModelDropdown() {
  appPicker?.classList.remove("open");
  appDropdown?.classList.remove("open");
}

if (appPicker) {
  appPicker.addEventListener("click", (e) => {
    e.stopPropagation();
    appDropdown?.classList.contains("open") ? closeAppModelDropdown() : openAppModelDropdown();
  });
}
document.addEventListener("click", (e) => {
  if (appDropdown && !appDropdown.contains(e.target) && e.target !== appPicker) {
    closeAppModelDropdown();
  }
});

// ─── File upload ──────────────────────────────────────────────────────────────
// Files are stored in-memory until the next message is sent, then attached and cleared.
// Images are read as base64 (sent to provider's multimodal field).
// Text files are read as plain text and appended to the message as fenced code blocks.
// Max 5 files per message to keep payloads reasonable.
const uploadedFiles = []; // { name, type, data } — data is base64 for images, text for others

const uploadFileBtn = document.getElementById("uploadFileBtn");
const fileInput = document.getElementById("fileInput");
const filePreviewBar = document.getElementById("filePreviewBar");

if (uploadFileBtn) uploadFileBtn.addEventListener("click", () => fileInput?.click());

/**
 * Re-renders the file preview bar above the message input.
 * Shows image thumbnails or a file icon + name + remove button for each queued file.
 * Hides the bar entirely when uploadedFiles is empty.
 */
function renderFilePreviews() {
  if (!filePreviewBar) return;
  if (!uploadedFiles.length) { filePreviewBar.style.display = "none"; filePreviewBar.innerHTML = ""; return; }
  filePreviewBar.style.display = "flex";
  filePreviewBar.innerHTML = uploadedFiles.map((f, i) => {
    const isImg = f.type.startsWith("image/");
    const thumb = isImg ? `<img src="data:${f.type};base64,${f.data}" alt="">` : `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`;
    return `<div class="file-chip">${thumb}<span class="file-chip-name" title="${esc(f.name)}">${esc(f.name)}</span><span class="file-chip-rm" data-idx="${i}">×</span></div>`;
  }).join("");
  filePreviewBar.querySelectorAll(".file-chip-rm").forEach(btn => {
    btn.addEventListener("click", () => {
      uploadedFiles.splice(Number(btn.dataset.idx), 1);
      renderFilePreviews();
    });
  });
}

if (fileInput) {
  fileInput.addEventListener("change", async () => {
    const files = Array.from(fileInput.files || []);
    for (const file of files) {
      if (uploadedFiles.length >= 5) break; // cap at 5 attachments
      const isImg = file.type.startsWith("image/");
      const data = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = e => {
          if (isImg) {
            // strip "data:image/xxx;base64," prefix
            resolve(e.target.result.split(",")[1]);
          } else {
            resolve(e.target.result);
          }
        };
        reader.onerror = reject;
        if (isImg) reader.readAsDataURL(file);
        else reader.readAsText(file);
      });
      uploadedFiles.push({ name: file.name, type: file.type, data });
    }
    fileInput.value = "";
    renderFilePreviews();
  });
}

loadState().then(() => {
  updateWriteToggle();
  checkOllama();
});
