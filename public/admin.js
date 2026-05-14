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
  if ($("metricRoles")) $("metricRoles").textContent = state.roles?.length || 0;
  if ($("metricPermissions")) $("metricPermissions").textContent = state.permissions?.length || 0;
  if ($("metricGovernedModels")) $("metricGovernedModels").textContent = state.models.filter(m => m.governanceTier).length;
  if ($("metricExternalAccess")) $("metricExternalAccess").textContent = state.settings.allowApiModels ? "On" : "Off";
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
    $("modelTableBody").innerHTML = `<tr><td colspan="8" style="text-align:center; color:var(--muted); padding:24px;">No local models discovered. Check Ollama connection in Settings.</td></tr>`;
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
      <td>
        <select class="select-input input-sm" data-model-tier="${esc(m.id)}" style="min-width:140px;">
          ${["approved-local", "restricted", "offline-only", "gpu-restricted", "experimental", "deprecated"].map(t => `<option value="${t}" ${m.governanceTier === t ? "selected" : ""}>${t}</option>`).join("")}
        </select>
        ${m.sensitiveAllowed ? "" : `<span class="badge badge-red" style="font-size:10px; margin-left:4px;">no sensitive prompts</span>`}
      </td>
      <td>
        <div class="table-sub">${esc(m.resourceTier || "standard")} · ${m.gpuRequired ? "GPU" : "CPU/GPU"}</div>
        <div class="table-sub">Concurrency ${esc(m.maxConcurrency || 2)}</div>
        <button class="btn btn-ghost btn-xs" data-save-model="${esc(m.id)}">Save policy</button>
      </td>
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
    const isAdmin = u.role === "admin";
    const deactivateBtn = isAdmin
      ? ""
      : `<button class="btn ${isActive ? "btn-ghost" : "btn-secondary"} btn-xs" data-toggle="${esc(u.id)}" data-next="${isActive ? "0" : "1"}">${isActive ? "Deactivate" : "Activate"}</button>`;
    return `<div class="user-item">
      <div class="user-item-avatar" style="${isActive ? "" : "opacity:0.5;"}">${esc(av)}</div>
      <div class="user-item-info">
        <div class="user-item-name">${esc(u.name)} ${isAdmin ? '<span class="badge badge-indigo" style="font-size:10px;">admin</span>' : ""} ${!isActive ? '<span class="badge badge-amber" style="font-size:10px;">inactive</span>' : ""}</div>
        <div class="user-item-meta">${esc(u.email || "")} · ${esc(dept?.name || "No dept")} · ${esc(u.aiAccessTier || "standard")} · ${esc(u.dailyTokenLimit || 0)} daily tokens</div>
      </div>
      <div class="user-item-actions">
        <button class="btn btn-secondary btn-xs" data-change-pw="${esc(u.id)}" data-name="${esc(u.name)}">Change Password</button>
        ${deactivateBtn}
      </div>
    </div>`;
  }).join("");
}

function renderAccessControl() {
  if (!$("accessUserSelect")) return;
  $("accessUserSelect").innerHTML = state.users.map(u => `<option value="${esc(u.id)}">${esc(u.name)} · ${esc(u.email)}</option>`).join("");
  $("overridePermission").innerHTML = (state.permissions || []).map(p => `<option value="${esc(p.key)}">${esc(p.key)} · ${esc(p.riskLevel)}</option>`).join("");
  $("roleMatrixBody").innerHTML = (state.roles || []).map(role => `
    <tr>
      <td><div class="table-name">${esc(role.name)}</div><div class="table-sub">${esc(role.id)}</div></td>
      <td>${esc(role.description || "")}</td>
      <td>${(role.permissions || []).map(p => {
        const cat = p.split(":")[0];
        const cls = {models:"badge-blue",chat:"badge-green",files:"badge-amber",workspace:"badge-indigo",admin:"badge-red",users:"badge-indigo",audit:"badge-gray",api:"badge-blue",agents:"badge-blue",ollama:"badge-blue"}[cat] || "badge-gray";
        return `<span class="badge ${cls}" style="font-size:11px;margin:2px;">${esc(p)}</span>`;
      }).join("")}</td>
    </tr>
  `).join("");
  renderEffectiveAccess();
}

async function renderEffectiveAccess() {
  if (!$("effectiveAccessPanel")) return;
  const user = state.users.find(u => u.id === $("accessUserSelect").value) || state.users[0];
  if (!user) {
    $("effectiveAccessPanel").innerHTML = `<div class="empty-state">No users found.</div>`;
    return;
  }
  const dept = state.departments.find(d => d.id === user.departmentId);
  let access = null;
  try {
    const result = await api(`/api/admin/users/${encodeURIComponent(user.id)}/effective-access`);
    access = result?.effectiveAccess;
  } catch {
    access = null;
  }
  const allowedIds = new Set(access?.allowedModelIds || []);
  const allowedModels = state.models.filter(m => allowedIds.has(m.id));
  const permissions = new Set(access?.permissions || user.rights || []);
  $("effectiveAccessPanel").innerHTML = `
    <div class="access-summary">
      <div><span class="access-label">Department</span><strong>${esc(dept?.name || "No department")}</strong></div>
      <div><span class="access-label">Tier</span><strong>${esc(user.aiAccessTier || "standard")}</strong></div>
      <div><span class="access-label">GPU quota</span><strong>${esc(user.gpuQuotaMinutes || 0)} min</strong></div>
      <div><span class="access-label">Risk</span><strong>${esc(user.securityRiskScore || 0)}/100</strong></div>
    </div>
    <div class="access-section-title">AI Usage Permissions</div>
    <div class="badge-wrap">${Array.from(permissions).sort().map(p => `<span class="badge badge-green">${esc(p)}</span>`).join("")}</div>
    <div class="access-section-title">Approved Models</div>
    <div class="badge-wrap">${allowedModels.slice(0, 12).map(m => `<span class="badge badge-blue">${esc(m.name)}</span>`).join("") || `<span class="badge badge-amber">No approved models</span>`}</div>
    <div class="access-section-title">Resource Limits</div>
    <div class="quota-grid">
      <div>Daily tokens <strong>${esc(user.dailyTokenLimit || 0)}</strong></div>
      <div>Monthly tokens <strong>${esc(user.monthlyTokenLimit || 0)}</strong></div>
      <div>VRAM limit <strong>${esc(user.vramLimitMb || 0)} MB</strong></div>
      <div>Context <strong>${esc(user.maxContextSize || 0)}</strong></div>
    </div>
  `;
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
  renderAccessControl();
  renderSettings();
  renderAudit();
}

async function loadState() {
  state = await api("/api/state");
  if (!state) return;
  renderAll();
}

async function checkOllama() {
  const label = $("ollamaStatus");
  const dot = $("ollamaStatusDot");
  try {
    const data = await api("/api/ollama/models");
    if (!data) return;
    if (data.ok) {
      if (label) label.textContent = "Ollama connected";
      if (dot) dot.className = "status-dot ok";
    } else {
      if (label) label.textContent = "Ollama not connected";
      if (dot) dot.className = "status-dot off";
    }
  } catch {
    if (label) label.textContent = "Ollama not connected";
    if (dot) dot.className = "status-dot off";
  }
}

// === TAB NAVIGATION ===
const tabTitles = {
  overview: "Company Dashboard",
  models: "Local Models",
  users: "User Management",
  access: "Access Control",
  settings: "System Settings",
  audit: "Audit Trail",
};

function switchTab(tab) {
  activeTab = tab;
  $all(".tab-view").forEach(v => v.classList.remove("active"));
  const tabEl = document.getElementById("tab-" + tab);
  if (tabEl) tabEl.classList.add("active");
  $all(".nav-item[data-tab]").forEach(b => b.classList.remove("active"));
  const navEl = document.querySelector(".nav-item[data-tab=\"" + tab + "\"]");
  if (navEl) navEl.classList.add("active");
  const titleEl = $("tabTitle");
  if (titleEl) titleEl.textContent = tabTitles[tab] || tab;
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

$("modelTableBody").addEventListener("click", async (e) => {
  const modelId = e.target.dataset.saveModel;
  if (!modelId) return;
  const tier = document.querySelector(`[data-model-tier="${CSS.escape(modelId)}"]`)?.value || "approved-local";
  e.target.disabled = true;
  e.target.textContent = "Saving…";
  try {
    await api(`/api/admin/models/${encodeURIComponent(modelId)}/governance`, {
      method: "PATCH",
      body: JSON.stringify({
        governanceTier: tier,
        gpuRequired: tier === "gpu-restricted",
        resourceTier: tier === "gpu-restricted" ? "gpu-heavy" : tier === "offline-only" ? "private" : "standard",
        sensitiveAllowed: tier !== "experimental",
      }),
    });
    await loadState();
  } finally {
    e.target.disabled = false;
    e.target.textContent = "Save policy";
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
  const modelList = $("ollamaTestModels");
  btn.disabled = true;
  btn.textContent = "Testing…";
  msg.className = "form-message";
  msg.textContent = "";
  modelList.style.display = "none";
  modelList.innerHTML = "";
  try {
    const result = await api("/api/admin/model-sources/test", {
      method: "POST",
      body: JSON.stringify({ ollamaUrl: $("ollamaUrl").value.trim() }),
    });
    if (result.ok) {
      msg.className = "form-message success";
      msg.textContent = `Connected — ${result.count} model${result.count === 1 ? "" : "s"} found.`;
      if (result.models?.length) {
        modelList.style.display = "block";
        modelList.innerHTML = `
          <div style="font-size:12px; font-weight:600; color:var(--muted); margin-bottom:8px; text-transform:uppercase; letter-spacing:.05em;">Models on this server</div>
          <div style="display:grid; gap:6px;">
            ${result.models.map(m => `
              <div style="display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:var(--bg,#F8F9FB); border:1px solid var(--border); border-radius:8px;">
                <span style="font-size:13px; font-weight:500;">${esc(m.name || m.model || m)}</span>
                <span class="badge badge-green" style="font-size:10px;">available</span>
              </div>`).join("")}
          </div>`;
      }
    } else {
      msg.className = "form-message error";
      msg.textContent = "Cannot connect to Ollama at this URL.";
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
        designation: $("newUserDesignation").value.trim(),
        team: $("newUserTeam").value.trim(),
        aiAccessTier: $("newUserTier").value,
        dailyTokenLimit: Number($("newUserDailyTokens").value || 50000),
        rights: role === "admin"
          ? ["admin:manage", "chat:use", "models:manage", "users:manage"]
          : $("newUserTier").value === "developer"
            ? ["chat:use", "models:local:use", "models:coding:use", "workspace:build"]
            : ["chat:use", "models:local:use"],
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

if ($("accessUserSelect")) {
  $("accessUserSelect").addEventListener("change", renderEffectiveAccess);
}

if ($("saveOverrideBtn")) {
  $("saveOverrideBtn").addEventListener("click", async () => {
    const msg = $("overrideMsg");
    const userId = $("accessUserSelect").value;
    msg.className = "form-message";
    msg.textContent = "";
    try {
      await api(`/api/admin/users/${userId}/overrides`, {
        method: "POST",
        body: JSON.stringify({
          permissionKey: $("overridePermission").value,
          effect: $("overrideEffect").value,
          reason: $("overrideReason").value.trim(),
          expiresAt: $("overrideExpires").value,
        }),
      });
      msg.className = "form-message success";
      msg.textContent = "Override applied.";
      $("overrideReason").value = "";
      $("overrideExpires").value = "";
      await loadState();
    } catch (err) {
      msg.className = "form-message error";
      msg.textContent = err.message;
    }
  });
}

// User list actions (change password / toggle active)
$("userList").addEventListener("click", async (e) => {
  const msg = $("userMsg2");
  const changePwId = e.target.dataset.changePw;
  const toggleId = e.target.dataset.toggle;

  if (changePwId) {
    openChangePwModal(changePwId, e.target.dataset.name || "User");
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

// === CHANGE PASSWORD MODAL ===
let changePwUserId = null;
let captchaExpected = null;

function generateCaptcha() {
  const ops = ["+", "-", "×"];
  const op = ops[Math.floor(Math.random() * ops.length)];
  let a = Math.floor(Math.random() * 9) + 1;
  let b = Math.floor(Math.random() * 9) + 1;
  if (op === "-" && b > a) [a, b] = [b, a]; // keep result positive
  captchaExpected = op === "+" ? a + b : op === "-" ? a - b : a * b;
  $("captchaLabel").textContent = `Security check: what is ${a} ${op} ${b}?`;
  $("captchaAnswer").value = "";
}

function openChangePwModal(userId, name) {
  changePwUserId = userId;
  $("changePwTarget").textContent = `Changing password for ${name}`;
  $("changePwNew").value = "";
  $("changePwConfirm").value = "";
  $("changePwMsg").textContent = "";
  $("changePwMsg").className = "form-message";
  generateCaptcha();
  const modal = $("changePwModal");
  modal.style.display = "flex";
}

function closeChangePwModal() {
  $("changePwModal").style.display = "none";
  changePwUserId = null;
  captchaExpected = null;
}

$("closePwModal").addEventListener("click", closeChangePwModal);
$("changePwModal").addEventListener("click", (e) => {
  if (e.target === $("changePwModal")) closeChangePwModal();
});

$("changePwForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = $("changePwMsg");
  msg.className = "form-message";
  msg.textContent = "";

  const newPw = $("changePwNew").value;
  const confirmPw = $("changePwConfirm").value;
  const answer = parseInt($("captchaAnswer").value.trim(), 10);

  if (newPw.length < 12) {
    msg.className = "form-message error";
    msg.textContent = "Password must be at least 12 characters.";
    return;
  }
  if (newPw !== confirmPw) {
    msg.className = "form-message error";
    msg.textContent = "Passwords do not match.";
    return;
  }
  if (isNaN(answer) || answer !== captchaExpected) {
    msg.className = "form-message error";
    msg.textContent = "Incorrect security answer. Try again.";
    generateCaptcha();
    return;
  }

  try {
    await api(`/api/admin/users/${changePwUserId}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ password: newPw }),
    });
    msg.className = "form-message success";
    msg.textContent = "Password changed successfully.";
    setTimeout(closeChangePwModal, 1500);
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
    generateCaptcha();
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
