let state = null;
let activeMode = "ask";
let accountOpen = false;
let workspaceConfigOpen = false;

const $ = (id) => document.getElementById(id);
const $q = (sel) => document.querySelector(sel);
const $all = (sel) => Array.from(document.querySelectorAll(sel));

function esc(v) {
  return String(v ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

async function api(path, opts = {}) {
  const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  if (res.status === 401) { window.location.href = "/login"; return null; }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

function initials(name) {
  return (name || "?").split(" ").map(w => w[0]).join("").slice(0, 2).toUpperCase();
}

function allowedModels() {
  if (!state) return [];
  return state.models.filter(m =>
    state.allowedModelIds.includes(m.id) &&
    m.status !== "disabled" &&
    !(m.provider === "api" && !state.settings.allowApiModels)
  );
}

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

function renderSidebar() {
  const u = state.activeUser;
  $("userAvatar").textContent = initials(u.name);
  $("userName").textContent = u.name;
  const dept = state.departments.find(d => d.id === u.departmentId);
  $("userMeta").textContent = `${u.role} · ${dept?.name || "Workspace"}`;
  $("adminLink").style.display = u.role === "admin" ? "flex" : "none";

  // Topbar
  $("topbarTitle").textContent = `${u.name}'s Workspace`;
  $("topbarSub").textContent = `${dept?.name || "General"} · Auto Router active`;

  // Models
  const models = allowedModels();
  $("sidebarModels").innerHTML = models.length
    ? models.map(m => `<div class="model-item"><div class="model-dot"></div><div class="model-name" title="${esc(m.name)}">${esc(m.name)}</div></div>`).join("")
    : `<div class="model-item"><div class="model-dot" style="background:var(--sidebar-muted)"></div><div class="model-name" style="color:var(--sidebar-muted)">No approved models</div></div>`;

  // Model select in composer
  $("manualModel").innerHTML = `<option value="">Auto Router</option>` +
    models.map(m => `<option value="${esc(m.id)}">${esc(m.name)}</option>`).join("");

  // Workspace
  const ws = state.workspace;
  if (ws) {
    $("workspacePath").textContent = ws.outputFolder || ws.workspaceRoot || "Not set";
    const modeLabels = { default: "Approve writes", review: "Auto-review", full: "Full access" };
    $("workspaceModeTag").innerHTML = `<span class="workspace-mode-tag">${modeLabels[ws.permissionMode] || ws.permissionMode}</span>`;
    $("workspaceFolderInput").value = ws.workspaceRoot || "";
    $("permissionModeSelect").value = ws.permissionMode || "default";
  }

  // Access policy
  const rights = u.rights || [];
  $("accessPolicy").innerHTML = `
    <strong>${u.email || ""}</strong><br>
    ${dept?.name || "No department"} · ${u.role}<br><br>
    <span style="font-size:11px;">Rights: ${rights.map(r => `<span class="badge badge-indigo" style="margin:1px 2px;">${esc(r)}</span>`).join(" ")}</span><br><br>
    <span style="font-size:12px;">${models.length} model${models.length !== 1 ? "s" : ""} approved — access comes from your user, group, and department grants.</span>
  `;
}

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
        ${(msg.artifacts || []).map(a => `<span class="artifact-chip"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>${esc(a.name)}</span>`).join("")}
      </div>` : "";

    return `<div class="message-wrap ${msg.role}">
      <div class="message-meta">${meta}</div>
      <div class="message-bubble">${esc(msg.content)}</div>
      ${footer}
    </div>`;
  }).join("");

  $("messages").scrollTop = $("messages").scrollHeight;
}

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

async function loadState() {
  state = await api("/api/state");
  if (!state) return;
  renderSidebar();
  renderMessages();
}

async function checkOllama() {
  const pill = $("ollamaStatus");
  try {
    const data = await api("/api/ollama/models");
    if (!data) return;
    if (data.ok) {
      pill.textContent = `${data.models.length} model${data.models.length === 1 ? "" : "s"} live`;
      pill.className = "status-pill ok";
    } else {
      const cached = state?.models?.filter(m => m.provider === "ollama").length || 0;
      pill.textContent = cached ? `Ollama offline · ${cached} cached` : "Ollama not connected";
      pill.className = "status-pill off";
    }
  } catch {
    pill.textContent = "Ollama not connected";
    pill.className = "status-pill off";
  }
}

function updateWriteToggle() {
  const isBuildFix = ["build", "fix"].includes(activeMode);
  const label = $("writeToggleLabel");
  label.style.display = isBuildFix ? "flex" : "none";
  const ws = state?.workspace;
  if (ws?.permissionMode === "full") {
    $("writeToWorkspace").checked = true;
    label.classList.add("enabled");
  }
}

// Events
$all(".mode-btn[data-mode]").forEach(btn => {
  btn.addEventListener("click", () => {
    activeMode = btn.dataset.mode;
    $all(".mode-btn[data-mode]").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    updateWriteToggle();
  });
});

$("writeToWorkspace").addEventListener("change", (e) => {
  $("writeToggleLabel").classList.toggle("enabled", e.target.checked);
});

$("newChatBtn").addEventListener("click", async () => {
  await api("/api/chat/clear", { method: "POST", body: "{}" });
  renderRouter(null);
  await loadState();
});

$("chatForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = $("messageInput");
  const message = input.value.trim();
  if (!message) return;

  const btn = $("sendBtn");
  btn.disabled = true;
  btn.textContent = "Sending…";
  input.value = "";

  $("routerContent").innerHTML = `<div class="router-body" style="display:flex;align-items:center;gap:8px;color:var(--muted);font-size:13px;">
    <div style="width:14px;height:14px;border:2px solid var(--border);border-top-color:var(--primary);border-radius:50%;animation:spin 0.7s linear infinite;"></div>
    Routing…
  </div>
  <style>@keyframes spin{to{transform:rotate(360deg)}}</style>`;

  try {
    const result = await api("/api/chat", {
      method: "POST",
      body: JSON.stringify({
        message,
        mode: activeMode,
        manualModelId: $("manualModel").value || null,
        writeToWorkspace: $("writeToWorkspace").checked,
      }),
    });
    renderRouter(result.route);
    await loadState();
  } catch (err) {
    $("routerContent").innerHTML = `<div class="router-body"><div style="font-size:13px;color:var(--danger);">${esc(err.message)}</div></div>`;
  } finally {
    btn.disabled = false;
    btn.textContent = "Send";
  }
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

loadState().then(() => {
  updateWriteToggle();
  checkOllama();
});
