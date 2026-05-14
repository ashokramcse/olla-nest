let state = null;
let activeTab = "overview";

const $ = (id) => document.getElementById(id);
const $all = (sel) => Array.from(document.querySelectorAll(sel));

function esc(v) {
  return String(v ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

async function api(path, opts = {}) {
  const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  if (res.status === 401) { window.location.href = "/login"; return null; }
  if (res.status === 403) { window.location.href = "/app"; return null; }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

function initials(name) {
  return (name || "A").split(" ").map(w => w[0]).join("").slice(0, 2).toUpperCase();
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

// === RENDER FUNCTIONS ===

function renderIdentity() {
  const u = state.activeUser;
  $("adminAvatar").textContent = initials(u.name);
  $("adminName").textContent = u.name;
}

function renderOverview() {
  const localModels = state.models.filter(m => m.provider === "ollama" && m.status === "available");
  $("metricModels").textContent = localModels.length;
  $("metricUsers").textContent = state.users.length;
  $("metricGroups").textContent = state.groups.length;
  $("metricDepts").textContent = state.departments.length;
}

function capBadges(caps) {
  const colors = {
    coding: "badge-indigo", debugging: "badge-indigo", build: "badge-indigo",
    medical: "badge-red", ocr: "badge-blue", vision: "badge-blue",
    reasoning: "badge-green", analysis: "badge-green",
    general: "badge-default", ask: "badge-default",
  };
  return (caps || []).slice(0, 4).map(c =>
    `<span class="badge ${colors[c] || "badge-default"}" style="margin:1px 2px;">${esc(c)}</span>`
  ).join("");
}

function renderModels() {
  const models = state.models.filter(m => m.provider === "ollama");
  if (!models.length) {
    $("modelTableBody").innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--muted); padding:24px;">No local models discovered. Check Ollama connection in Settings.</td></tr>`;
    return;
  }
  $("modelTableBody").innerHTML = models.map(m => {
    const statusBadge = m.status === "available"
      ? `<span class="badge badge-green">${esc(m.status)}</span>`
      : m.status === "missing"
        ? `<span class="badge badge-amber">${esc(m.status)}</span>`
        : `<span class="badge badge-default">${esc(m.status)}</span>`;

    const speedBar = `<div style="display:flex;align-items:center;gap:6px;">
      <div style="width:60px;height:4px;background:var(--border);border-radius:2px;overflow:hidden;">
        <div style="width:${m.speedScore}%;height:100%;background:var(--primary);border-radius:2px;"></div>
      </div>
      <span style="font-size:11px;color:var(--muted);">${m.speedScore}</span>
    </div>`;

    const qualityBar = `<div style="display:flex;align-items:center;gap:6px;">
      <div style="width:60px;height:4px;background:var(--border);border-radius:2px;overflow:hidden;">
        <div style="width:${m.qualityScore}%;height:100%;background:var(--success);border-radius:2px;"></div>
      </div>
      <span style="font-size:11px;color:var(--muted);">${m.qualityScore}</span>
    </div>`;

    return `<tr>
      <td>
        <div class="table-name">${esc(m.name)}</div>
        <div class="table-sub">${esc(m.model || m.name)}</div>
      </td>
      <td><span class="badge badge-blue">${esc(m.provider)}</span></td>
      <td>${statusBadge}</td>
      <td>${speedBar}</td>
      <td>${qualityBar}</td>
      <td>${capBadges(m.capabilities)}</td>
    </tr>`;
  }).join("");
}

function renderUsers() {
  // Populate department select
  $("newUserDept").innerHTML = state.departments.map(d =>
    `<option value="${esc(d.id)}">${esc(d.name)}</option>`
  ).join("");

  // User list
  $("userList").innerHTML = state.users.map(u => {
    const dept = state.departments.find(d => d.id === u.departmentId);
    const av = initials(u.name);
    const isActive = u.active;
    return `<div class="user-item">
      <div class="user-item-avatar" style="${isActive ? "" : "opacity:0.5;"}">${esc(av)}</div>
      <div class="user-item-info">
        <div class="user-item-name">${esc(u.name)} ${!isActive ? '<span class="badge badge-amber" style="font-size:10px;">inactive</span>' : ""}</div>
        <div class="user-item-meta">${esc(u.email || "")} · ${esc(u.role)} · ${esc(dept?.name || "No dept")}</div>
      </div>
      <div class="user-item-actions">
        <button class="btn btn-secondary btn-xs" data-reset="${esc(u.id)}" title="Reset password">↺ Reset</button>
        <button class="btn ${isActive ? "btn-ghost" : "btn-secondary"} btn-xs" data-toggle="${esc(u.id)}" data-next="${isActive ? "0" : "1"}">
          ${isActive ? "Deactivate" : "Activate"}
        </button>
      </div>
    </div>`;
  }).join("");
}

function maskKey(el, isSet) {
  el.value = isSet ? "••••••••" : "";
  el.dataset.masked = isSet ? "1" : "0";
}

function renderSettings() {
  const s = state.settings;
  $("routerEnabled").checked = !!s.routerEnabled;
  $("allowApiModels").checked = !!s.allowApiModels;
  $("localOnlyDefault").checked = !!s.localOnlyDefault;
  $("localWritesEnabled").checked = !!s.localWritesEnabled;
  $("localPermissionMode").value = s.localPermissionMode || "default";
  $("workspaceRoot").value = s.workspaceRoot || "";
  $("ollamaUrl").value = s.ollamaUrl || "http://host.docker.internal:11434";
  // Provider fields
  $("anthropicEnabled").checked = !!s.anthropicEnabled;
  maskKey($("anthropicApiKey"), s.anthropicApiKey === "set");
  $("anthropicBaseUrl").value = s.anthropicBaseUrl || "";
  $("openaiEnabled").checked = !!s.openaiEnabled;
  maskKey($("openaiApiKey"), s.openaiApiKey === "set");
  $("openaiBaseUrl").value = s.openaiBaseUrl || "";
  $("groqEnabled").checked = !!s.groqEnabled;
  maskKey($("groqApiKey"), s.groqApiKey === "set");
  $("customEnabled").checked = !!s.customEnabled;
  $("customName").value = s.customName || "";
  maskKey($("customApiKey"), s.customApiKey === "set");
  $("customBaseUrl").value = s.customBaseUrl || "";
  renderSourcePills();
}

function renderSourcePills() {
  const s = state.settings;
  const active = [
    { name: "Ollama", cls: "badge-green" },
    s.anthropicEnabled && { name: "Anthropic", cls: "badge-blue" },
    s.openaiEnabled && { name: "OpenAI", cls: "badge-blue" },
    s.groqEnabled && { name: "Groq", cls: "badge-blue" },
    s.customEnabled && { name: s.customName || "Custom", cls: "badge-default" },
  ].filter(Boolean);
  $("activeSourcePills").innerHTML = active.map(p =>
    `<span class="badge ${p.cls}" style="font-size:10px;">${esc(p.name)}</span>`
  ).join("");
}

function renderAudit() {
  const items = state.audit || [];
  if (!items.length) {
    $("auditList").innerHTML = `<div style="text-align:center; padding:32px; color:var(--muted); font-size:13px;">No audit events yet.</div>`;
    return;
  }
  $("auditList").innerHTML = items.map(item => `
    <div class="audit-item">
      <div class="audit-dot"></div>
      <div style="flex:1; min-width:0;">
        <div class="audit-actor">${esc(item.actor)}</div>
        <div class="audit-detail">${esc(item.detail)}</div>
      </div>
      <div class="audit-time">${timeAgo(item.createdAt)}</div>
    </div>`).join("");
}

function renderAll() {
  renderIdentity();
  renderOverview();
  renderModels();
  renderUsers();
  renderSettings();
  renderAudit();
}

async function loadState() {
  state = await api("/api/state");
  if (!state) return;
  renderAll();
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

// === TAB NAVIGATION ===
const tabTitles = {
  overview: "Company Dashboard",
  models: "Local Models",
  users: "User Management",
  settings: "System Settings",
  audit: "Audit Trail",
};

function switchTab(tab) {
  activeTab = tab;
  $all(".tab-view").forEach(v => v.classList.remove("active"));
  $(`tab-${tab}`).classList.add("active");
  $all(".nav-item[data-tab]").forEach(b => b.classList.remove("active"));
  document.querySelector(`.nav-item[data-tab="${tab}"]`).classList.add("active");
  $("tabTitle").textContent = tabTitles[tab] || tab;
}

$all(".nav-item[data-tab]").forEach(btn => {
  btn.addEventListener("click", () => switchTab(btn.dataset.tab));
});

// === EVENTS ===

$("logoutBtn").addEventListener("click", async () => {
  await api("/api/auth/logout", { method: "POST", body: "{}" });
  window.location.href = "/login";
});

$("refreshBtn").addEventListener("click", async () => {
  await checkOllama();
  await loadState();
});

$("syncModelsBtn").addEventListener("click", async () => {
  const btn = $("syncModelsBtn");
  btn.disabled = true;
  btn.textContent = "Syncing…";
  try {
    await api("/api/ollama/models");
    await loadState();
  } finally {
    btn.disabled = false;
    btn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg> Sync from Ollama`;
  }
});

// Save router/model settings
$("saveSettingsBtn").addEventListener("click", async () => {
  const msg = $("settingsMsg");
  msg.className = "form-message";
  msg.textContent = "";
  try {
    await api("/api/admin/settings", {
      method: "POST",
      body: JSON.stringify({
        routerEnabled: $("routerEnabled").checked,
        allowApiModels: $("allowApiModels").checked,
        localOnlyDefault: $("localOnlyDefault").checked,
        localWritesEnabled: $("localWritesEnabled").checked,
        localPermissionMode: $("localPermissionMode").value,
        workspaceRoot: $("workspaceRoot").value.trim() || undefined,
        apiModelProvider: $("apiModelProvider").value,
      }),
    });
    msg.className = "form-message success";
    msg.textContent = "Settings saved.";
    await loadState();
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
});

// Test Ollama connection
$("testOllamaBtn").addEventListener("click", async () => {
  const btn = $("testOllamaBtn");
  const msg = $("modelSourceMsg");
  btn.disabled = true;
  btn.textContent = "Testing…";
  msg.className = "form-message";
  msg.textContent = "";
  try {
    const result = await api("/api/admin/model-sources/test", {
      method: "POST",
      body: JSON.stringify({ ollamaUrl: $("ollamaUrl").value.trim() }),
    });
    if (result.ok) {
      msg.className = "form-message success";
      msg.textContent = `Connected — ${result.count} model${result.count === 1 ? "" : "s"} found.`;
    } else {
      msg.className = "form-message error";
      msg.textContent = `Cannot connect: ${result.error}`;
    }
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  } finally {
    btn.disabled = false;
    btn.textContent = "Test connection";
  }
});

// Save Ollama URL
$("saveModelSourceBtn").addEventListener("click", async () => {
  const msg = $("modelSourceMsg");
  msg.className = "form-message";
  msg.textContent = "";
  try {
    await api("/api/admin/settings", {
      method: "POST",
      body: JSON.stringify({ ollamaUrl: $("ollamaUrl").value.trim() }),
    });
    msg.className = "form-message success";
    msg.textContent = "Ollama URL saved.";
    await loadState();
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
});

// Add Employee toggle
$("toggleAddUserBtn").addEventListener("click", () => {
  const panel = $("addUserPanel");
  const open = panel.style.display !== "none";
  panel.style.display = open ? "none" : "block";
  $("toggleAddUserBtn").textContent = open ? "+ Add Employee" : "− Cancel";
});

$("cancelAddUserBtn").addEventListener("click", () => {
  $("addUserPanel").style.display = "none";
  $("toggleAddUserBtn").textContent = "+ Add Employee";
  $("createUserForm").reset();
});

// Create user
$("createUserForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = $("userMsg");
  const role = $("newUserRole").value;
  msg.className = "form-message";
  msg.textContent = "";
  try {
    await api("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({
        name: $("newUserName").value.trim(),
        email: $("newUserEmail").value.trim(),
        role,
        departmentId: $("newUserDept").value,
        password: $("newUserPassword").value || undefined,
        rights: role === "admin"
          ? ["admin:manage", "chat:use", "models:manage", "users:manage"]
          : ["chat:use"],
      }),
    });
    e.target.reset();
    $("addUserPanel").style.display = "none";
    $("toggleAddUserBtn").textContent = "+ Add Employee";
    msg.className = "form-message success";
    msg.textContent = "Account created successfully.";
    await loadState();
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
});

// User list actions (reset password / toggle active)
$("userList").addEventListener("click", async (e) => {
  const msg = $("userMsg2");
  const resetId = e.target.dataset.reset;
  const toggleId = e.target.dataset.toggle;

  if (resetId) {
    try {
      await api(`/api/admin/users/${resetId}/reset-password`, {
        method: "POST",
        body: JSON.stringify({ password: "UserDemo!12345" }),
      });
      msg.className = "form-message success";
      msg.textContent = "Password reset to: UserDemo!12345";
    } catch (err) {
      msg.className = "form-message error";
      msg.textContent = err.message;
    }
  }

  if (toggleId) {
    try {
      await api(`/api/admin/users/${toggleId}`, {
        method: "PATCH",
        body: JSON.stringify({ active: e.target.dataset.next === "1" }),
      });
      await loadState();
    } catch (err) {
      msg.className = "form-message error";
      msg.textContent = err.message;
    }
  }
});

// Model Sources toggle
$("toggleSourcesBtn").addEventListener("click", () => {
  const panel = $("modelSourcesPanel");
  const open = panel.style.display !== "none";
  panel.style.display = open ? "none" : "block";
  $("toggleSourcesBtn").textContent = open ? "Configure Sources" : "Close";
});

// Clear masked key on focus so user can type a new value
["anthropicApiKey", "openaiApiKey", "groqApiKey", "customApiKey"].forEach(id => {
  const el = $(id);
  if (!el) return;
  el.addEventListener("focus", () => {
    if (el.dataset.masked === "1") { el.value = ""; el.dataset.masked = "0"; }
  });
});

async function saveProvider(fields, msgId) {
  const msg = $(msgId);
  msg.className = "form-message";
  msg.textContent = "";
  try {
    await api("/api/admin/settings", { method: "POST", body: JSON.stringify(fields) });
    msg.className = "form-message success";
    msg.textContent = "Saved.";
    await loadState();
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
}

$("saveAnthropicBtn").addEventListener("click", () => {
  const fields = { anthropicEnabled: $("anthropicEnabled").checked, anthropicBaseUrl: $("anthropicBaseUrl").value.trim() };
  if ($("anthropicApiKey").dataset.masked !== "1") fields.anthropicApiKey = $("anthropicApiKey").value;
  saveProvider(fields, "anthropicMsg");
});

$("saveOpenaiBtn").addEventListener("click", () => {
  const fields = { openaiEnabled: $("openaiEnabled").checked, openaiBaseUrl: $("openaiBaseUrl").value.trim() };
  if ($("openaiApiKey").dataset.masked !== "1") fields.openaiApiKey = $("openaiApiKey").value;
  saveProvider(fields, "openaiMsg");
});

$("saveGroqBtn").addEventListener("click", () => {
  const fields = { groqEnabled: $("groqEnabled").checked };
  if ($("groqApiKey").dataset.masked !== "1") fields.groqApiKey = $("groqApiKey").value;
  saveProvider(fields, "groqMsg");
});

$("saveCustomBtn").addEventListener("click", () => {
  const fields = { customEnabled: $("customEnabled").checked, customName: $("customName").value.trim(), customBaseUrl: $("customBaseUrl").value.trim() };
  if ($("customApiKey").dataset.masked !== "1") fields.customApiKey = $("customApiKey").value;
  saveProvider(fields, "customMsg");
});

loadState().then(checkOllama);
