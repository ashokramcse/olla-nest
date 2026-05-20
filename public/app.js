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
  const ct = res.headers.get("content-type") || "";
  if (!ct.includes("application/json")) {
    if (!res.ok) throw new Error(`Server error ${res.status}`);
    return null;
  }
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
 * allowed to use AND that are currently reachable (status === "available").
 * "missing" models are excluded — Ollama is unreachable so they cannot be used.
 * Also hides API models when allowApiModels is false globally.
 *
 * @returns {object[]} Array of usable model objects from state.
 */
function allowedModels() {
  if (!state) return [];
  return state.models.filter(m =>
    state.allowedModelIds.includes(m.id) &&
    m.status === "available" &&
    !(m.provider === "api" && !state.settings.allowApiModels)
  );
}

/**
 * Returns ALL models the user has been granted access to, regardless of
 * current reachability. Used for the sidebar "Approved Models" list so
 * the user can see what is configured even when Ollama is offline.
 *
 * @returns {object[]} Array of configured model objects (may include missing).
 */
function configuredModels() {
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

  // Show user's first name on the topbar Account button
  const accountBtnName = document.getElementById("accountBtnName");
  if (accountBtnName) accountBtnName.textContent = firstName || u.name || "Profile";
  $("topbarTitle").textContent = `Welcome, ${firstName}`;
  $("topbarSub").textContent = `${dept?.name || "General"} · Auto Router active`;
  const statWs = document.getElementById("statWorkspace");
  if (statWs) { const ws2 = state.workspace; statWs.textContent = ws2?.workspaceRoot ? (ws2.workspaceRoot.split("/").pop() || "set") : "not set"; }
  const infoDept = document.getElementById("infoDept");
  if (infoDept) infoDept.textContent = dept?.name || "—";
  const roleMini = document.querySelector(".role-chip");
  if (roleMini) roleMini.textContent = u.role.charAt(0).toUpperCase() + u.role.slice(1);

  // Sidebar model list — only show models that are currently available (reachable).
  // If Ollama is offline, show a clear "offline" message instead of a ghost list.
  const availableModels = allowedModels();
  if (availableModels.length) {
    $("sidebarModels").innerHTML = availableModels.map(m =>
      `<div class="model-item">
        <div class="model-dot" style="flex-shrink:0;"></div>
        <div class="model-name" title="${esc(m.name)}">${esc(m.name)}</div>
      </div>`
    ).join("");
  } else {
    $("sidebarModels").innerHTML = `<div style="font-size:12px;color:var(--mute);padding:4px 0;line-height:1.5;">
      Ollama offline — no models available.<br>
      <span style="font-size:11px;">Start Ollama or add a cloud provider.</span>
    </div>`;
  }

  // Repopulate the Claude-style picker dropdown — only AVAILABLE models shown
  populateAppModelPicker(availableModels);

  // Model connected status pill
  const statModelPill = document.getElementById("statModelPill");
  const statModelName = document.getElementById("statModelName");
  if (statModelPill && statModelName) {
    const activeM = availableModels[0];
    if (activeM) {
      statModelName.textContent = activeM.name;
      statModelPill.style.display = "flex";
    } else {
      statModelPill.style.display = "none";
    }
  }

  // No-model warning banner in composer — shown when Ollama is down and no cloud models available
  const noModelBanner = document.getElementById("noModelBanner");
  if (noModelBanner) {
    noModelBanner.style.display = availableModels.length === 0 ? "flex" : "none";
  }
  // Disable send when no models available
  const sendBtn = document.getElementById("sendBtn");
  if (sendBtn) sendBtn.disabled = availableModels.length === 0;

  // Token usage pill
  loadTokenUsage();

  // Effective permissions — must be declared before any use below
  const effectivePermissions = (state.effectiveAccess?.permissions) || u.rights || [];

  // Terminal FAB — only show for users with workspace:build or admin
  const termFab = document.getElementById("termToggle");
  if (termFab) {
    const canTerminal = u.role === "admin" || effectivePermissions.includes("workspace:build");
    termFab.style.display = canTerminal ? "flex" : "none";
  }

  // Update model ring infographic — show available (reachable) count
  if (typeof updateModelRing === "function") updateModelRing(availableModels.length, 10);
  const capCard = document.getElementById("capabilityCard");
  if (capCard) capCard.style.display = availableModels.length > 0 ? "block" : "none";

  // Workspace
  const ws = state.workspace;
  if (ws) {
    const wsFullPath = ws.outputFolder || ws.workspaceRoot || "";
    const wsEl = $("workspacePath");
    if (wsFullPath) {
      // Show friendly label: /host-home/... → ~/...
      const displayPath = wsFullPath.startsWith("/host-home/")
        ? "~/" + wsFullPath.slice("/host-home/".length)
        : wsFullPath;
      wsEl.textContent = displayPath;
      wsEl.title = wsFullPath;
    } else {
      wsEl.textContent = "Not configured";
    }
    const modeLabels = { default: "Approve writes", review: "Auto-review", full: "Full access" };
    $("workspaceModeTag").innerHTML = `<span class="ws-mode-tag">${modeLabels[ws.permissionMode] || ws.permissionMode}</span>`;
    $("workspaceFolderInput").value = ws.workspaceRoot || "";
    $("permissionModeSelect").value = ws.permissionMode || "default";
  }

  // accessPolicy element (hidden panel, kept for compatibility)
  const modelsApproved = availableModels.length;
  $("accessPolicy").innerHTML = `
    <strong>${u.email || ""}</strong><br>
    ${dept?.name || "No department"} · ${u.role}<br><br>
    <span style="font-size:11px;">Rights: ${effectivePermissions.map(r => `<span class="badge badge-indigo" style="margin:1px 2px;">${esc(r)}</span>`).join(" ")}</span><br><br>
    <span style="font-size:12px;">${modelsApproved} model${modelsApproved !== 1 ? "s" : ""} approved — access comes from your user, group, and department grants.</span>
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
    accessRightsEl.innerHTML = effectivePermissions.map(r =>
      `<span class="right-badge">${esc(rightLabels[r] || r)}</span>`
    ).join("") || `<span style="font-size:12px;color:var(--mute);">No rights configured</span>`;
  }

  // Terminal note in access card
  const termNote = document.getElementById("sidebarTerminalNote");
  const canTerminal = u.role === "admin" || effectivePermissions.includes("workspace:build");
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
    const isActive = c.isActive === 1 || c.isActive === true;
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

// ─── Confirm dialog ──────────────────────────────────────────────────────────

/**
 * Show a lightweight confirm dialog overlay.
 * @param {string} message - Confirmation prompt text.
 * @param {Function} onConfirm - Async callback executed if user confirms.
 */
function showConfirm(message, onConfirm) {
  const existing = document.getElementById("confirmOverlay");
  if (existing) existing.remove();

  const overlay = document.createElement("div");
  overlay.id = "confirmOverlay";
  overlay.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;";
  overlay.innerHTML = `
    <div style="background:#1e1e2e;border:1px solid #333;border-radius:12px;padding:24px 28px;max-width:360px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,.5);">
      <p style="margin:0 0 20px;color:#e2e8f0;font-size:14px;line-height:1.5;">${esc(message)}</p>
      <div style="display:flex;gap:10px;justify-content:flex-end;">
        <button id="confirmCancel" style="padding:7px 16px;border-radius:8px;border:1px solid #444;background:transparent;color:#aaa;cursor:pointer;font-size:13px;">Cancel</button>
        <button id="confirmOk" style="padding:7px 16px;border-radius:8px;border:none;background:#ef4444;color:#fff;cursor:pointer;font-size:13px;font-weight:600;">Delete</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  const close = () => overlay.remove();
  overlay.addEventListener("click", e => { if (e.target === overlay) close(); });
  document.getElementById("confirmCancel").addEventListener("click", close);
  document.getElementById("confirmOk").addEventListener("click", async () => {
    close();
    await onConfirm();
  });
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
// Language badge colors — matches common languages visually
const LANG_COLORS = {
  js: "#f7df1e", javascript: "#f7df1e",
  ts: "#3178c6", typescript: "#3178c6",
  py: "#3572a5", python: "#3572a5",
  rb: "#cc342d", ruby: "#cc342d",
  go: "#00add8",
  rs: "#dea584", rust: "#dea584",
  java: "#b07219",
  cs: "#178600", csharp: "#178600",
  cpp: "#f34b7d", c: "#555555",
  php: "#4f5d95",
  swift: "#ffac45",
  kt: "#a97bff", kotlin: "#a97bff",
  sql: "#e38c00",
  html: "#e34c26", css: "#563d7c", scss: "#c6538c",
  sh: "#89e051", bash: "#89e051", shell: "#89e051", zsh: "#89e051", fish: "#89e051",
  json: "#cbcb41", yaml: "#cb171e", yml: "#cb171e", toml: "#9c4221",
  md: "#083fa1", markdown: "#083fa1",
  dockerfile: "#384d54",
  xml: "#0060ac",
  r: "#198ce7",
  dart: "#00b4ab",
  lua: "#000080",
  vim: "#019733",
  graphql: "#e10098",
};

/**
 * Renders a diff-style code block with per-line coloring.
 * Lines starting with '+' → green, '-' → red, '@@' → blue hunk header.
 */
function renderDiffBlock(text, lang) {
  const lines = text.split("\n");
  const rows = lines.map((line, i) => {
    let cls = "";
    if (/^@@/.test(line)) cls = "diff-hunk";
    else if (/^\+/.test(line)) cls = "diff-add";
    else if (/^-/.test(line)) cls = "diff-del";
    const safe = line.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    return `<div class="code-line ${cls}"><span class="code-ln">${i + 1}</span><span class="code-lc">${safe}</span></div>`;
  });
  return `<div class="code-lines">${rows.join("")}</div>`;
}

/**
 * Renders a code block with syntax highlighting (hljs), line numbers,
 * language badge, optional filename header, and Copy / Run buttons.
 */
function renderCodeBlock(text, lang) {
  const langKey = (lang || "").toLowerCase().replace(/^diff-/, "");
  const isDiff = lang && (lang.toLowerCase().startsWith("diff") || /^\+\+\+|^---/.test(text.trim()) || /^@@/.test(text.trim()));

  // Detect filename from first-line comment patterns: // filename: ..., # filename: ..., <!-- filename: -->
  let filename = "";
  const filenameMatch = text.match(/^(?:\/\/|#|<!--)\s*(?:filename|file):\s*([^\s\n>]+)/i);
  if (filenameMatch) filename = filenameMatch[1];

  // Badge
  const color = LANG_COLORS[langKey] || "#888";
  const badgeText = langKey || "text";
  const textColor = ["#f7df1e","#f34b7d","#ffac45","#cbcb41"].includes(color) ? "#111" : "#fff";
  const badge = `<span class="code-lang-badge" style="background:${color};color:${textColor}">${esc(badgeText)}</span>`;
  const fileEl = filename ? `<span class="code-filename">📄 ${esc(filename)}</span>` : "";

  // Runnable?
  const SHELL_LANGS = ["bash","sh","shell","zsh","fish","console","terminal","cmd","powershell"];
  const isRunnable = SHELL_LANGS.includes(langKey);
  const runBtn = isRunnable
    ? `<button class="run-in-term-btn" onclick="runInTerminal(this)" title="Run in terminal"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="5 3 19 12 5 21 5 3"/></svg> Run</button>`
    : "";
  const viewBtn = `<button class="md-copy-btn" onclick="openCodeReview(this)" title="Full screen view">⛶ View</button>`;

  // Diff rendering
  if (isDiff) {
    const codeEl = renderDiffBlock(text, langKey);
    return `<div class="md-code-block" data-lang="${esc(langKey)}" data-filename="${esc(filename)}" data-raw="${encodeURIComponent(text)}">
      <div class="code-header">${badge}${fileEl}<div class="code-header-actions">${viewBtn}<button class="md-copy-btn" onclick="copyCode(this)">Copy</button>${runBtn}</div></div>
      <pre><code>${codeEl}</code></pre>
    </div>`;
  }

  // Syntax highlighted with line numbers
  let highlighted;
  if (typeof hljs !== "undefined" && langKey) {
    try {
      const validLang = hljs.getLanguage(langKey) ? langKey : null;
      highlighted = validLang
        ? hljs.highlight(text, { language: validLang }).value
        : hljs.highlightAuto(text).value;
    } catch (e) {
      highlighted = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }
  } else {
    highlighted = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  // Build line-numbered table from highlighted HTML
  // Split on newlines preserving hljs spans across lines isn't trivial, so we highlight then split plain + overlay
  const plainLines = text.split("\n");
  // Re-highlight line by line would lose multi-line context; instead use full block highlight and split on \n
  const hlLines = highlighted.split("\n");
  // If trailing empty line, remove it
  if (hlLines[hlLines.length - 1] === "") hlLines.pop();

  const rows = hlLines.map((hlLine, i) => {
    return `<div class="code-line"><span class="code-ln">${i + 1}</span><span class="code-lc">${hlLine}</span></div>`;
  });

  return `<div class="md-code-block" data-lang="${esc(langKey)}" data-filename="${esc(filename)}" data-raw="${encodeURIComponent(text)}">
    <div class="code-header">${badge}${fileEl}<div class="code-header-actions">${viewBtn}<button class="md-copy-btn" onclick="copyCode(this)">Copy</button>${runBtn}</div></div>
    <pre><code class="hljs"><div class="code-lines">${rows.join("")}</div></code></pre>
  </div>`;
}

function renderMarkdown(content) {
  if (typeof marked === "undefined") return `<pre style="white-space:pre-wrap;">${esc(content)}</pre>`;
  marked.setOptions({ breaks: true, gfm: true });
  const renderer = new marked.Renderer();
  renderer.code = ({ text, lang }) => renderCodeBlock(text, lang);
  const raw = marked.parse(content, { renderer });
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
  const block = btn.closest(".md-code-block");
  // Gather plain text from line cells (ignoring line number cells) or fall back to full code innerText
  const lineCells = block.querySelectorAll(".code-lc");
  let text;
  if (lineCells.length) {
    text = Array.from(lineCells).map(el => el.innerText).join("\n");
  } else {
    text = block.querySelector("code")?.innerText || "";
  }
  navigator.clipboard.writeText(text).then(() => {
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
  const block = btn.closest(".md-code-block");
  const lineCells = block.querySelectorAll(".code-lc");
  const code = (lineCells.length
    ? Array.from(lineCells).map(el => el.innerText).join("\n")
    : block.querySelector("code")?.innerText || "").trim();
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

// ── Code Review Modal ──────────────────────────────────────────────────────────
let _reviewRawText = "";

function openCodeReview(btn) {
  const block = btn.closest(".md-code-block");
  const lang = block.dataset.lang || "text";
  const filename = block.dataset.filename || "";
  const raw = decodeURIComponent(block.dataset.raw || "");
  _reviewRawText = raw;

  const modal = document.getElementById("codeReviewModal");
  const langEl = document.getElementById("codeReviewLang");
  const fileEl = document.getElementById("codeReviewFilename");
  const codeEl = document.getElementById("codeReviewContent");
  const linesEl = document.getElementById("codeReviewLines");

  // Badge color
  const color = LANG_COLORS[lang] || "#888";
  const textColor = ["#f7df1e","#f34b7d","#ffac45","#cbcb41"].includes(color) ? "#111" : "#fff";
  langEl.textContent = lang;
  langEl.style.background = color;
  langEl.style.color = textColor;
  fileEl.textContent = filename || "";

  // Highlight
  let highlighted = raw.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  if (typeof hljs !== "undefined" && lang) {
    try {
      const validLang = hljs.getLanguage(lang) ? lang : null;
      if (validLang) highlighted = hljs.highlight(raw, { language: validLang }).value;
    } catch (_) {}
  }
  codeEl.innerHTML = highlighted;
  linesEl.textContent = raw.split("\n").length + " lines";
  modal.style.display = "flex";
  document.body.style.overflow = "hidden";
}

function closeCodeReview() {
  document.getElementById("codeReviewModal").style.display = "none";
  document.body.style.overflow = "";
}

function copyReviewCode() {
  navigator.clipboard.writeText(_reviewRawText).then(() => {
    const btn = document.getElementById("codeReviewCopyBtn");
    btn.textContent = "Copied!";
    setTimeout(() => { btn.textContent = "Copy"; }, 2000);
  });
}

// Close modal on backdrop click
document.getElementById("codeReviewModal")?.addEventListener("click", (e) => {
  if (e.target === document.getElementById("codeReviewModal")) closeCodeReview();
});

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
    $("routerContent").innerHTML = `<div style="font-size:12px;color:var(--mute);line-height:1.6;">Send a message to see which model handled your request.</div>`;
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
async function loadState(retries = 3) {
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      const data = await api("/api/state");
      if (data) {
        state = data;
        window.state = state;
        renderSidebar();
        renderMessages();
        break;
      }
    } catch (err) {
      console.warn(`[loadState] attempt ${attempt}/${retries} failed:`, err.message);
      if (attempt === retries) {
        // Show error state in user card so it never stays "Loading..."
        const nameEl = document.getElementById("userName");
        if (nameEl && nameEl.textContent === "Loading…") {
          nameEl.textContent = "Could not load";
          const metaEl = document.getElementById("userMeta");
          if (metaEl) metaEl.textContent = "Refresh to retry";
        }
      } else {
        await new Promise(r => setTimeout(r, 1500 * attempt));
      }
    }
  }
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
    const pct = data.dailyTokenLimit > 0 ? Math.min(100, Math.round((data.tokensUsedToday / data.dailyTokenLimit) * 100)) : 0;
    used.textContent = data.tokensUsedToday.toLocaleString();
    limit.textContent = data.dailyTokenLimit > 0 ? data.dailyTokenLimit.toLocaleString() : "∞";
    bar.style.width = pct + "%";
    bar.className = "token-bar-fill" + (pct >= 90 ? " over" : pct >= 70 ? " warn" : "");
  } catch {}
}

/**
 * Checks Ollama connectivity via the fast /api/ollama/ping endpoint (2s timeout,
 * no DB sync) and updates the status dot + label in the topbar.
 * Called once on boot and every 30 seconds via setInterval.
 */
let _ollamaWasOnline = null; // tracks previous state to detect transitions
async function checkOllama() {
  const label = $("ollamaStatus");
  const dot = $("ollamaStatusDot");
  try {
    const data = await api("/api/ollama/ping");
    if (data?.ok) {
      if (label) label.textContent = "Ollama connected";
      if (dot) dot.className = "status-dot ok";
      // Only reload state when Ollama transitions from offline → online
      if (_ollamaWasOnline === false) loadState();
      _ollamaWasOnline = true;
    } else {
      if (label) label.textContent = "Ollama offline";
      if (dot) dot.className = "status-dot off";
      _ollamaWasOnline = false;
    }
  } catch {
    if (label) label.textContent = "Ollama offline";
    if (dot) dot.className = "status-dot off";
    _ollamaWasOnline = false;
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
  // Save to input history (deduplicate consecutive identical messages)
  if (inputHistory[inputHistory.length - 1] !== message) inputHistory.push(message);
  historyIdx = -1;
  historyDraft = "";
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
      <div class="message-bubble assistant-bubble md-body" id="${asstBubbleId}-content"><div class="thinking-indicator"><span class="thinking-dot"></span><span class="thinking-dot"></span><span class="thinking-dot"></span><span class="thinking-phase" id="${asstBubbleId}-phase">Routing…</span></div></div>
    </div>
  `);
  msgs.scrollTop = msgs.scrollHeight;

  let fullContent = "";
  let currentRoute = null;
  let firstToken = true;

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
            // Update thinking phase label
            const phase = $(`${asstBubbleId}-phase`);
            if (phase) phase.textContent = "Thinking…";
          } else if (event.type === "token") {
            fullContent += event.content;
            const bubble = $(`${asstBubbleId}-content`);
            if (bubble) {
              if (firstToken) {
                firstToken = false;
                bubble.innerHTML = renderMarkdown(fullContent) + '<span class="streaming-cursor"></span>';
              } else {
                bubble.innerHTML = renderMarkdown(fullContent) + '<span class="streaming-cursor"></span>';
              }
            }
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

// Account button — opens the profile drawer (same as clicking the sidebar user card)
$("accountBtn").addEventListener("click", () => {
  const trigger = document.getElementById("appProfileBtn");
  if (trigger) trigger.click();
});


// Workspace config
const ICON_PENCIL = `<svg id="configWorkspaceIcon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>`;
const ICON_CLOSE  = `<svg id="configWorkspaceIcon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`;

function setWorkspacePanel(open) {
  workspaceConfigOpen = open;
  $("workspaceConfigPanel").style.display = open ? "block" : "none";
  $("configWorkspaceBtn").innerHTML = open ? ICON_CLOSE : ICON_PENCIL;
}

$("configWorkspaceBtn").addEventListener("click", () => {
  const opening = !workspaceConfigOpen;
  setWorkspacePanel(opening);
  if (opening) {
    // OS detection — always runs when panel opens
    const ua = navigator.userAgent || "";
    const isWin = ua.includes("Windows");
    const isLinux = ua.includes("Linux") && !ua.includes("Android");
    const defaultSuggest = isWin
      ? "/host-home/Documents/my-project"
      : isLinux
        ? "/host-home/projects/my-project"
        : "/host-home/Documents/my-project";

    // OS hint — always update
    const hint = $("wsOsHint");
    if (hint) {
      if (isWin) {
        hint.innerHTML = `Windows: <code style="font-size:11px;">C:\\Users\\you\\Documents\\project</code> → <code style="font-size:11px;">/host-home/Documents/project</code>`;
      } else if (isLinux) {
        hint.innerHTML = `Linux: <code style="font-size:11px;">~/projects/myapp</code> → <code style="font-size:11px;">/host-home/projects/myapp</code>`;
      } else {
        hint.innerHTML = `macOS: <code style="font-size:11px;">~/Documents/myproject</code> → <code style="font-size:11px;">/host-home/Documents/myproject</code>`;
      }
    }

    // Pre-fill input: use saved path only if it's a valid container path.
    // If the saved path is an old host path (e.g. /Users/...), discard it and use the OS default.
    const saved = state?.workspace?.workspaceRoot || "";
    const isValidContainerPath = saved.startsWith("/host-home") || saved.startsWith("/app/data");
    $("workspaceFolderInput").value = isValidContainerPath ? saved : defaultSuggest;
  }
});

$("cancelWorkspaceBtn").addEventListener("click", () => { setWorkspacePanel(false); });

// ── Folder Browser ────────────────────────────────────────────────────────────
let wsBrowserOpen = false;

async function wsBrowseTo(browsePath) {
  try {
    const url = "/api/workspace/browse" + (browsePath ? "?path=" + encodeURIComponent(browsePath) : "");
    const data = await api(url);
    const list = $("wsBrowserList");
    const pathEl = $("wsBrowserPath");
    // Show friendly path
    pathEl.textContent = data.current.startsWith("/host-home")
      ? "~" + data.current.slice("/host-home".length)
      : data.current;

    let html = "";
    if (data.parent) {
      html += `<div class="ws-browser-item" onclick="wsBrowseTo(${JSON.stringify(data.parent)})" style="color:var(--mute);">⬆ ..</div>`;
    }
    // Show "Select this folder" option
    html += `<div class="ws-browser-item ws-browser-select" onclick="wsSelectFolder(${JSON.stringify(data.current)})">✅ Select <em>${data.current.split("/").pop() || "/"}</em></div>`;
    data.dirs.forEach(d => {
      html += `<div class="ws-browser-item" onclick="wsBrowseTo(${JSON.stringify(d.path)})">📁 ${esc(d.name)}</div>`;
    });
    if (!data.dirs.length && !data.parent) html += `<div style="padding:8px 12px;font-size:12px;color:var(--mute);">No sub-folders found.</div>`;
    list.innerHTML = html;
  } catch (e) {
    $("wsBrowserList").innerHTML = `<div style="padding:8px 12px;font-size:12px;color:red;">${esc(e.message)}</div>`;
  }
}

function wsSelectFolder(folderPath) {
  $("workspaceFolderInput").value = folderPath;
  // Close browser
  $("wsFolderBrowser").style.display = "none";
  $("wsBrowseBtn").textContent = "📂 Browse folders";
  wsBrowserOpen = false;
}

$("wsBrowseBtn").addEventListener("click", async () => {
  wsBrowserOpen = !wsBrowserOpen;
  const browser = $("wsFolderBrowser");
  browser.style.display = wsBrowserOpen ? "block" : "none";
  $("wsBrowseBtn").textContent = wsBrowserOpen ? "✕ Close browser" : "📂 Browse folders";
  if (wsBrowserOpen) {
    // Start at current value or host-home root
    const current = $("workspaceFolderInput").value.trim() || "/host-home";
    const startPath = current.startsWith("/host-home") || current.startsWith("/app/data") ? current : "/host-home";
    await wsBrowseTo(startPath);
  }
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
      setWorkspacePanel(false);
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

// Input history — ↑/↓ to navigate sent messages (like a terminal)
const inputHistory = [];
let historyIdx = -1;
let historyDraft = ""; // saves current draft when navigating up

// Textarea auto-resize + Enter to send + ↑↓ history
$("messageInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    $("chatForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    return;
  }
  const inp = $("messageInput");
  // ArrowUp: navigate to older history (only when cursor is on first line or field is empty)
  if (e.key === "ArrowUp") {
    const atTop = inp.selectionStart === 0 || !inp.value.includes("\n");
    if (!atTop) return;
    if (inputHistory.length === 0) return;
    e.preventDefault();
    if (historyIdx === -1) historyDraft = inp.value; // save current draft
    historyIdx = Math.min(historyIdx + 1, inputHistory.length - 1);
    inp.value = inputHistory[inputHistory.length - 1 - historyIdx];
    inp.selectionStart = inp.selectionEnd = inp.value.length;
    return;
  }
  // ArrowDown: navigate to newer history
  if (e.key === "ArrowDown") {
    if (historyIdx === -1) return;
    e.preventDefault();
    historyIdx--;
    if (historyIdx === -1) {
      inp.value = historyDraft; // restore draft
    } else {
      inp.value = inputHistory[inputHistory.length - 1 - historyIdx];
    }
    inp.selectionStart = inp.selectionEnd = inp.value.length;
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
  const models = allowedModels();
  populateAppModelPicker(models);
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

// Fire Ollama status check immediately — don't wait for loadState() so
// the status chip resolves in ≤2s regardless of how long state takes.
checkOllama();
setInterval(checkOllama, 30000);

loadState().then(() => {
  updateWriteToggle();
});
