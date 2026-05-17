let state = null;
let activeTab = "overview";

// Human-readable permission labels with descriptions (for tooltips + UI)
const PERM_META = {
  "chat:use":                  { label: "Chat Access",           desc: "Send messages and chat with AI models",                     color: "badge-green",  group: "core"      },
  "files:upload":              { label: "File Upload",           desc: "Upload files and documents to conversations",               color: "badge-amber",  group: "core"      },
  "models:local:use":          { label: "Local AI Models",       desc: "Use Ollama models running on your server",                  color: "badge-blue",   group: "models"    },
  "models:coding:use":         { label: "Coding Models",         desc: "Use code-specialised models for programming tasks",         color: "badge-indigo", group: "models"    },
  "models:reasoning:use":      { label: "Reasoning Models",      desc: "Use advanced reasoning and analysis models",                color: "badge-indigo", group: "models"    },
  "models:external:use":       { label: "External AI APIs",      desc: "Use cloud AI providers (OpenAI, Anthropic, Groq, etc.)",    color: "badge-blue",   group: "models"    },
  "workspace:build":           { label: "Terminal & Workspace",  desc: "Open the built-in terminal and write files to the workspace", color: "badge-amber", group: "workspace" },
  "tools:call":                { label: "Tool Calls",            desc: "Use AI tool-calling and function execution",                color: "badge-indigo", group: "workspace" },
  "api:use":                   { label: "API Access",            desc: "Access the Olla Nest API directly",                         color: "badge-blue",   group: "workspace" },
  "agents:run":                { label: "Run AI Agents",         desc: "Execute autonomous AI agent workflows",                     color: "badge-indigo", group: "workspace" },
  "admin:manage":              { label: "Admin Control",         desc: "Full administrative control over the entire platform",      color: "badge-red",    group: "admin"     },
  "users:manage":              { label: "Manage Users",          desc: "Create, edit, and deactivate employee accounts",            color: "badge-red",    group: "admin"     },
  "models:manage":             { label: "Manage Models",         desc: "Approve, restrict, and configure AI models",                color: "badge-red",    group: "admin"     },
  "audit:read":                { label: "View Audit Logs",       desc: "Read system audit trails and security logs",                color: "badge-gray",   group: "admin"     },
  "ollama:models:pull":        { label: "Download Models",       desc: "Download new AI models from the Ollama registry",           color: "badge-blue",   group: "admin"     },
  "ollama:models:import":      { label: "Import Models",         desc: "Import custom AI models into Ollama",                      color: "badge-blue",   group: "admin"     },
  "ollama:modelfile:create":   { label: "Create Modelfiles",     desc: "Create custom Ollama model configurations",                 color: "badge-blue",   group: "admin"     },
};

function permLabel(key) {
  return PERM_META[key]?.label || key;
}
function permDesc(key) {
  return PERM_META[key]?.desc || key;
}
function permColor(key) {
  return PERM_META[key]?.color || "badge-gray";
}
function permBadge(key, extra) {
  return `<span class="badge ${permColor(key)}" title="${esc(permDesc(key))}" style="cursor:help;${extra||""}">${esc(permLabel(key))}</span>`;
}

const $ = (id) => document.getElementById(id);
const $all = (sel) => Array.from(document.querySelectorAll(sel));

function esc(v) {
  return String(v ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

async function api(path, opts = {}) {
  const headers = { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", ...(opts.headers || {}) };
  const res = await fetch(path, { ...opts, headers });
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

let editingUserId = null;

function renderUsers() {
  $("newUserDept").innerHTML = state.departments.map(d =>
    `<option value="${esc(d.id)}">${esc(d.name)}</option>`
  ).join("");
  if (typeof populateTeamDropdown === "function") populateTeamDropdown();

  $("userList").innerHTML = state.users.map(u => {
    const dept = state.departments.find(d => d.id === u.departmentId);
    const av = initials(u.name);
    const isActive = u.active;
    const isAdmin = u.role === "admin";
    const isEditing = editingUserId === u.id;

    const editPanel = isEditing ? buildEditPanel(u) : "";

    return `<div class="user-item${isEditing ? " editing" : ""}" id="user-row-${esc(u.id)}">
      <div class="user-item-avatar" style="${isActive ? "" : "opacity:0.5;"}">${esc(av)}</div>
      <div class="user-item-info">
        <div class="user-item-name">${esc(u.name)}
          ${isAdmin ? '<span class="badge badge-red" style="font-size:10px;">admin</span>' : ""}
          ${!isActive ? '<span class="badge badge-amber" style="font-size:10px;">inactive</span>' : ""}
        </div>
        <div class="user-item-meta">${esc(u.email || "")} · ${esc(dept?.name || "No dept")} · ${esc(u.aiAccessTier || "standard")} · ${Number(u.dailyTokenLimit||0).toLocaleString()} daily tokens</div>
      </div>
      <div class="user-item-actions">
        <button class="btn ${isEditing ? "btn-secondary" : "btn-dark"} btn-xs" data-edit-user="${esc(u.id)}">${isEditing ? "✕ Close" : "✏ Edit"}</button>
      </div>
    </div>
    ${editPanel}`;
  }).join("");
}

function buildEditPanel(u) {
  const allPerms = Object.keys(PERM_META);
  const userRights = new Set(u.rights || []);
  const isAdmin = u.role === "admin";

  const groupDefs = [
    { key: "core",      label: "Core Access",         color: "#16a34a" },
    { key: "models",    label: "AI Models",            color: "#2563eb" },
    { key: "workspace", label: "Workspace & Tools",    color: "#d97706" },
    { key: "admin",     label: "Administration",       color: "#dc2626" },
  ];
  const permCheckboxes = groupDefs.map(group => {
    const groupPerms = allPerms.filter(k => PERM_META[k].group === group.key);
    if (!groupPerms.length) return "";
    const cards = groupPerms.map(key => {
      const checked = userRights.has(key) ? "checked" : "";
      const meta = PERM_META[key];
      const isHighRisk = ["admin:manage","users:manage","models:manage","workspace:build"].includes(key);
      return `<label class="perm-check${isHighRisk ? " perm-high-risk" : ""}" title="${esc(meta.desc)}" style="border-left:3px solid ${group.color}22;position:relative;">
        <input type="checkbox" name="right_${esc(key)}" value="${esc(key)}" ${checked} ${isAdmin && key === "admin:manage" ? "checked disabled" : ""}>
        <span class="perm-check-label">${esc(meta.label)}</span>
        <span class="perm-check-desc">${esc(meta.desc)}</span>
      </label>`;
    }).join("");
    return `<div style="font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:${group.color};margin:12px 0 6px;padding-bottom:4px;border-bottom:2px solid ${group.color}22;">${esc(group.label)}</div><div class="perm-grid">${cards}</div>`;
  }).join("");

  const deptOptions = state.departments.map(d =>
    `<option value="${esc(d.id)}" ${d.id === u.departmentId ? "selected" : ""}>${esc(d.name)}</option>`
  ).join("");

  const tierOptions = ["basic","standard","power","unlimited"].map(t =>
    `<option value="${t}" ${(u.aiAccessTier||"standard") === t ? "selected" : ""}>${t.charAt(0).toUpperCase()+t.slice(1)}</option>`
  ).join("");

  return `<div class="edit-user-panel" id="edit-panel-${esc(u.id)}">
    <div class="edit-user-panel-inner">
      <div class="edit-section-title">Basic Information</div>
      <div class="form-grid">
        <div>
          <label class="form-label">Full Name</label>
          <input class="input input-sm" id="eu-name-${esc(u.id)}" value="${esc(u.name)}" autocomplete="off" />
        </div>
        <div>
          <label class="form-label">Email</label>
          <input class="input input-sm" id="eu-email-${esc(u.id)}" type="email" value="${esc(u.email||"")}" autocomplete="off" />
        </div>
        <div>
          <label class="form-label">Role</label>
          <select class="input input-sm" id="eu-role-${esc(u.id)}" ${isAdmin ? "disabled" : ""}>
            <option value="user" ${u.role==="user"?"selected":""}>User</option>
            <option value="admin" ${u.role==="admin"?"selected":""}>Admin</option>
          </select>
        </div>
        <div>
          <label class="form-label">Department</label>
          <select class="input input-sm" id="eu-dept-${esc(u.id)}">${deptOptions}</select>
        </div>
        <div>
          <label class="form-label">Designation</label>
          <input class="input input-sm" id="eu-desig-${esc(u.id)}" value="${esc(u.designation||"")}" placeholder="e.g. Software Engineer" />
        </div>
        <div>
          <label class="form-label">AI Access Tier</label>
          <select class="input input-sm" id="eu-tier-${esc(u.id)}">${tierOptions}</select>
        </div>
        <div>
          <label class="form-label">Daily Token Limit</label>
          <input class="input input-sm" id="eu-dtok-${esc(u.id)}" type="number" value="${esc(u.dailyTokenLimit||50000)}" />
        </div>
        <div>
          <label class="form-label">Monthly Token Limit</label>
          <input class="input input-sm" id="eu-mtok-${esc(u.id)}" type="number" value="${esc(u.monthlyTokenLimit||1000000)}" />
        </div>
      </div>

      <div class="edit-section-title" style="margin-top:18px;">
        Permissions
        <span style="font-size:11px;font-weight:400;color:var(--mute);margin-left:8px;">Hover any permission to see what it does</span>
      </div>
      ${permCheckboxes}

      <div class="edit-panel-actions">
        <button class="btn btn-dark btn-sm" data-save-user="${esc(u.id)}">Save Changes</button>
        <button class="btn btn-secondary btn-sm" data-change-pw="${esc(u.id)}" data-name="${esc(u.name)}">Change Password</button>
        ${!isAdmin ? `<button class="btn btn-danger btn-sm" data-toggle="${esc(u.id)}" data-next="${u.active ? "0" : "1"}">${u.active ? "Deactivate" : "Activate"} Account</button>` : ""}
        <div id="eu-msg-${esc(u.id)}" class="form-msg" style="margin-left:auto;"></div>
      </div>
    </div>
  </div>`;
}

function renderAccessControl() {
  if (!$("accessUserSelect")) return;
  $("accessUserSelect").innerHTML = state.users.map(u => `<option value="${esc(u.id)}">${esc(u.name)} · ${esc(u.email)}</option>`).join("");
  if ($("overridePermission")) {
    $("overridePermission").innerHTML = (state.permissions || []).map(p =>
      `<option value="${esc(p.key)}">${esc(permLabel(p.key))} (${esc(p.riskLevel)} risk)</option>`
    ).join("");
  }
  $("roleMatrixBody").innerHTML = (state.roles || []).map(role => `
    <tr>
      <td><div class="table-name">${esc(role.name)}</div><div class="table-sub">${esc(role.id)}</div></td>
      <td>${esc(role.description || "")}</td>
      <td>${(role.permissions || []).map(p => permBadge(p, "font-size:11px;margin:2px;")).join("")}</td>
    </tr>
  `).join("");
  if ($("deptPermSelect")) {
    $("deptPermSelect").innerHTML = (state.departments || []).map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join("");
    renderDeptPermGrid();
  }
  loadActiveSessions();
  renderEffectiveAccess();
}

async function loadActiveSessions() {
  const el = $("activeSessionsList");
  if (!el) return;
  try {
    const data = await api("/api/admin/sessions/active");
    if (!data?.sessions?.length) {
      el.innerHTML = `<div style="padding:12px 0;font-size:13px;color:var(--mute);text-align:center;">No active sessions found.</div>`;
      return;
    }
    el.innerHTML = data.sessions.map(s => `
      <div class="session-item">
        <div class="session-item-info">
          <span class="session-item-name">${esc(s.name || s.email || "Unknown")}</span>
          <span class="session-item-email">${esc(s.email || "")}</span>
        </div>
        <div style="display:flex;align-items:center;gap:8px;">
          <span class="session-item-role">${esc(s.role || "user")}</span>
          ${s.userId !== state.me?.id ? `<button class="btn btn-danger btn-xs" onclick="forceLogoutUser('${esc(s.userId)}')">Logout</button>` : `<span style="font-size:11px;color:var(--mute);">(you)</span>`}
        </div>
      </div>
    `).join("");
  } catch {
    el.innerHTML = `<div style="padding:12px 0;font-size:13px;color:var(--danger);text-align:center;">Failed to load sessions.</div>`;
  }
}

async function forceLogoutUser(userId) {
  if (!confirm("Force logout this user?")) return;
  await api(`/api/admin/sessions/user/${encodeURIComponent(userId)}`, { method: "DELETE" });
  await loadActiveSessions();
}

async function renderDeptPermGrid() {
  const el = $("deptPermGrid");
  if (!el) return;
  const deptId = $("deptPermSelect")?.value;
  const dept = (state.departments || []).find(d => d.id === deptId);
  const currentRights = new Set(dept?.defaultRights || []);
  el.innerHTML = Object.entries(PERM_META).map(([key, meta]) => `
    <label class="perm-check${meta.riskLevel === "critical" || meta.riskLevel === "high" ? " perm-high-risk" : ""}" title="${esc(meta.desc)}" style="position:relative;">
      <input type="checkbox" class="dept-perm-check" data-key="${esc(key)}" ${currentRights.has(key) ? "checked" : ""}>
      <span class="perm-check-label">${esc(meta.label)}</span>
      <span class="perm-check-desc">${esc(meta.desc)}</span>
    </label>
  `).join("");
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
    <div class="badge-wrap">${Array.from(permissions).sort().map(p => permBadge(p)).join("")}</div>
    <div class="access-section-title">Approved Models</div>
    <div class="badge-wrap">${allowedModels.slice(0, 12).map(m => `<span class="badge badge-blue">${esc(m.name)}</span>`).join("") || `<span class="badge badge-amber">No approved models</span>`}</div>
    <div class="access-section-title">Resource Limits</div>
    <div class="quota-grid">
      <div>Daily tokens <strong>${Number(user.dailyTokenLimit || 0).toLocaleString()}</strong></div>
      <div>Monthly tokens <strong>${Number(user.monthlyTokenLimit || 0).toLocaleString()}</strong></div>
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
  renderRouterConfig();
  renderOllamaProvider();
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
  providers: "API Providers",
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
  if (tab === "providers") loadProviders();
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

// (Ollama test/save moved to Providers tab — see renderOllamaProvider)

// Add Employee toggle
$("toggleAddUserBtn").addEventListener("click", () => {
  const panel = $("addUserPanel");
  const open = panel.style.display !== "none";
  panel.style.display = open ? "none" : "block";
  $("toggleAddUserBtn").textContent = open ? "+ Add Employee" : "✕ Close";
});

// Show/hide password in create employee form
const _toggleNewPw = $("toggleNewUserPassword");
if (_toggleNewPw) {
  _toggleNewPw.addEventListener("click", () => {
    const pw = $("newUserPassword");
    const isHidden = pw.type === "password";
    pw.type = isHidden ? "text" : "password";
    _toggleNewPw.textContent = isHidden ? "Hide" : "Show";
  });
}

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
    const teamSel = $("newUserTeam");
    const teamVal = teamSel ? teamSel.value : "";
    const result = await api("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({
        name: $("newUserName").value.trim(),
        email: $("newUserEmail").value.trim(),
        role,
        departmentId: $("newUserDept").value,
        password: $("newUserPassword").value || undefined,
        designation: $("newUserDesignation").value.trim(),
        team: teamVal,
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
    // Show invite modal with credentials
    if (result.credentials && typeof showInviteModal === "function") {
      showInviteModal(result.credentials, result.user?.isEnterprise || false);
    }
  } catch (err) {
    msg.className = "form-message error";
    msg.textContent = err.message;
  }
});

if ($("accessUserSelect")) {
  $("accessUserSelect").addEventListener("change", renderEffectiveAccess);
}

if ($("deptPermSelect")) {
  $("deptPermSelect").addEventListener("change", renderDeptPermGrid);
}
if ($("saveDeptPermsBtn")) {
  $("saveDeptPermsBtn").addEventListener("click", async () => {
    const deptId = $("deptPermSelect")?.value;
    const checked = [...document.querySelectorAll(".dept-perm-check:checked")].map(el => el.dataset.key);
    const msg = $("deptPermMsg");
    try {
      await api(`/api/admin/departments/${encodeURIComponent(deptId)}/rights`, { method: "PATCH", body: JSON.stringify({ rights: checked }) });
      msg.textContent = "✓ Saved";
      msg.style.color = "var(--green-deep)";
      // update state so re-render is fresh
      const d = (state.departments || []).find(d => d.id === deptId);
      if (d) d.defaultRights = checked;
    } catch (err) {
      msg.textContent = "Failed: " + err.message;
      msg.style.color = "var(--danger)";
    }
    setTimeout(() => { if (msg) msg.textContent = ""; }, 3000);
  });
}
if ($("refreshSessionsBtn")) {
  $("refreshSessionsBtn").addEventListener("click", loadActiveSessions);
}
if ($("clearAllSessionsBtn")) {
  $("clearAllSessionsBtn").addEventListener("click", async () => {
    if (!confirm("This will force-logout all currently logged-in employees. You will stay logged in. Continue?")) return;
    // logout everyone except current admin
    const data = await api("/api/admin/sessions/active");
    for (const s of (data?.sessions || [])) {
      if (s.userId !== state.me?.id) {
        await api(`/api/admin/sessions/user/${encodeURIComponent(s.userId)}`, { method: "DELETE" });
      }
    }
    await loadActiveSessions();
  });
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
  const btn = e.target.closest("button");
  if (!btn) return;
  const msg = $("userMsg2");

  // Edit / close edit
  const editUserId = btn.dataset.editUser;
  if (editUserId) {
    editingUserId = editingUserId === editUserId ? null : editUserId;
    renderUsers();
    if (editingUserId) {
      const panel = document.getElementById(`edit-panel-${editUserId}`);
      if (panel) panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
    return;
  }

  // Save edits
  const saveUserId = btn.dataset.saveUser;
  if (saveUserId) {
    const msgEl = $(`eu-msg-${saveUserId}`);
    msgEl.textContent = "Saving…";
    msgEl.className = "form-msg";
    const rights = Array.from(document.querySelectorAll(`#edit-panel-${saveUserId} input[type=checkbox][name^=right_]:checked`))
      .map(cb => cb.value);
    try {
      await api(`/api/admin/users/${saveUserId}`, {
        method: "PATCH",
        body: JSON.stringify({
          name: $(`eu-name-${saveUserId}`).value.trim(),
          email: $(`eu-email-${saveUserId}`).value.trim(),
          role: $(`eu-role-${saveUserId}`).value,
          departmentId: $(`eu-dept-${saveUserId}`).value,
          designation: $(`eu-desig-${saveUserId}`).value.trim(),
          aiAccessTier: $(`eu-tier-${saveUserId}`).value,
          dailyTokenLimit: Number($(`eu-dtok-${saveUserId}`).value) || 50000,
          monthlyTokenLimit: Number($(`eu-mtok-${saveUserId}`).value) || 1000000,
          rights,
        }),
      });
      msgEl.textContent = "✓ Saved!";
      msgEl.className = "form-msg success";
      await loadState();
    } catch (err) {
      msgEl.textContent = err.message;
      msgEl.className = "form-msg error";
    }
    return;
  }

  // Change password
  const changePwId = btn.dataset.changePw;
  if (changePwId) {
    openChangePwModal(changePwId, btn.dataset.name || "User");
    return;
  }

  // Toggle active/deactivate
  const toggleId = btn.dataset.toggle;
  if (toggleId) {
    try {
      await api(`/api/admin/users/${toggleId}`, {
        method: "PATCH",
        body: JSON.stringify({ active: btn.dataset.next === "1" }),
      });
      editingUserId = null;
      await loadState();
    } catch (err) {
      if (msg) { msg.className = "form-message error"; msg.textContent = err.message; }
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

// (Model Sources panel removed — providers managed in Providers tab)

// === PROVIDERS ===

function renderOllamaProvider() {
  const urlEl = $("provOllamaUrl");
  if (!urlEl) return;
  const s = state?.settings || {};
  urlEl.value = s.ollamaUrl || "http://host.docker.internal:11434";

  const ollamaModels = (state?.models || []).filter(m => m.provider === "ollama");
  const hasAvailable = ollamaModels.some(m => m.status === "available");

  const dot = $("ollamaProvStatusDot");
  const txt = $("ollamaProvStatusText");
  if (dot) dot.style.background = hasAvailable ? "#4caf50" : "#aaa";
  if (txt) txt.textContent = hasAvailable ? "Connected" : "No models available";

  const listEl = $("ollamaProvModelList");
  if (listEl) {
    if (!ollamaModels.length) {
      listEl.innerHTML = `<span style="font-size:12px;color:#aaa;">No models synced yet.</span>`;
    } else {
      listEl.innerHTML = ollamaModels.map(m => {
        const color = m.status === "available" ? "#4caf50" : "#ffca28";
        const bg = m.status === "available" ? "#e8f5e9" : "#fff8e1";
        return `<span style="display:inline-flex;align-items:center;gap:5px;padding:4px 10px;border-radius:999px;background:${bg};border:1px solid ${color}40;font-size:12px;font-weight:500;color:#1a1a0e;">
          <span style="width:6px;height:6px;border-radius:50%;background:${color};flex-shrink:0;"></span>${esc(m.name)}
        </span>`;
      }).join("");
    }
  }
}

if (document.getElementById("provOllamaTestBtn")) {
  document.getElementById("provOllamaTestBtn").addEventListener("click", async () => {
    const btn = $("provOllamaTestBtn");
    const txt = $("ollamaProvStatusText");
    btn.disabled = true; btn.textContent = "Testing…";
    try {
      await checkOllama();
      await loadState();
      renderOllamaProvider();
    } finally {
      btn.disabled = false; btn.textContent = "Test";
    }
  });
}

if (document.getElementById("provOllamaSyncBtn")) {
  document.getElementById("provOllamaSyncBtn").addEventListener("click", async () => {
    const btn = $("provOllamaSyncBtn");
    btn.disabled = true; btn.textContent = "Syncing…";
    try {
      await api("/api/ollama/models");
      await loadState();
      renderOllamaProvider();
    } finally {
      btn.disabled = false; btn.textContent = "Sync Models";
    }
  });
}

if (document.getElementById("provOllamaSaveBtn")) {
  document.getElementById("provOllamaSaveBtn").addEventListener("click", async () => {
    const btn = $("provOllamaSaveBtn");
    btn.disabled = true; btn.textContent = "Saving…";
    try {
      await api("/api/admin/settings", {
        method: "POST",
        body: JSON.stringify({ ollamaUrl: $("provOllamaUrl").value.trim() }),
      });
      await loadState();
      renderOllamaProvider();
    } catch (err) {
      alert(err.message);
    } finally {
      btn.disabled = false; btn.textContent = "Save";
    }
  });
}

async function loadProviders() {
  renderOllamaProvider();
  const list = $("providerList");
  if (!list) return;
  try {
    const data = await api("/api/admin/providers");
    if (!data || !data.providers) return;
    if (!data.providers.length) {
      list.innerHTML = `<div style="padding:20px;text-align:center;color:var(--mute);">No providers configured. Add one above.</div>`;
      return;
    }
    list.innerHTML = data.providers.map(p => `
      <div class="provider-card" id="pcard-${esc(p.id)}">
        <div class="provider-head">
          <span class="provider-name">${esc(p.name)}</span>
          <div style="display:flex;gap:6px;align-items:center;">
            <span class="badge ${p.enabled ? 'badge-green' : 'badge-gray'}">${p.enabled ? 'enabled' : 'disabled'}</span>
            <span class="badge badge-blue">${esc(p.type)}</span>
            <span class="badge badge-default">${p.modelCount} models</span>
          </div>
        </div>
        <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px;">
          <button class="btn ghost sm" onclick="testProvider('${esc(p.id)}', this)">Test Connection</button>
          <button class="btn ghost sm" onclick="syncProviderModels('${esc(p.id)}', this)">Sync Models</button>
          <button class="btn ghost sm" onclick="toggleProvider('${esc(p.id)}', ${!p.enabled}, this)">${p.enabled ? 'Disable' : 'Enable'}</button>
          <button class="btn danger sm" onclick="deleteProvider('${esc(p.id)}', '${esc(p.name)}', this)">Delete</button>
        </div>
        <div class="form-msg" id="pcard-msg-${esc(p.id)}"></div>
        <div id="pcard-models-${esc(p.id)}" style="margin-top:10px;"></div>
      </div>
    `).join("");
  } catch (err) {
    list.innerHTML = `<div style="color:var(--danger);padding:16px;">${esc(err.message)}</div>`;
  }
}

async function testProvider(id, btn) {
  const msg = $(`pcard-msg-${id}`);
  btn.disabled = true; btn.textContent = "Testing…";
  msg.className = "form-msg";
  try {
    const result = await api(`/api/admin/providers/${encodeURIComponent(id)}/test`);
    msg.className = result.ok ? "form-msg success" : "form-msg error";
    msg.textContent = result.ok ? `Connected — ${result.latency_ms}ms` : `Failed: ${result.error}`;
  } catch (err) {
    msg.className = "form-msg error";
    msg.textContent = err.message;
  } finally {
    btn.disabled = false; btn.textContent = "Test Connection";
  }
}

async function syncProviderModels(id, btn) {
  const msg = $(`pcard-msg-${id}`);
  btn.disabled = true; btn.textContent = "Syncing…";
  msg.className = "form-msg";
  try {
    const syncResult = await api(`/api/admin/providers/${encodeURIComponent(id)}/sync`, { method: "POST", body: "{}" });
    const modelsResult = await api(`/api/admin/providers/${encodeURIComponent(id)}/models`);
    msg.className = "form-msg success";
    msg.textContent = `Synced ${syncResult.synced} models.`;
    const modelsDiv = $(`pcard-models-${id}`);
    if (modelsDiv && modelsResult.models?.length) {
      modelsDiv.innerHTML = `<div class="table-wrap" style="margin-top:8px;"><table><thead><tr><th>Model</th><th>Governance</th><th>Approved</th></tr></thead><tbody>
        ${modelsResult.models.map(m => `<tr>
          <td>${esc(m.display_name)}<br><span style="font-size:11px;color:var(--mute);">${esc(m.model_id)}</span></td>
          <td><select class="input input-sm" data-gov="${esc(m.id)}" style="min-width:100px;">
            ${["approved","restricted","experimental","deprecated"].map(t => `<option value="${t}" ${m.governance_tag===t?"selected":""}>${t}</option>`).join("")}
          </select></td>
          <td><input type="checkbox" ${m.isApproved?"checked":""} data-approve="${esc(m.id)}" /></td>
        </tr>`).join("")}
      </tbody></table></div>
      <button class="btn dark sm" style="margin-top:8px;" onclick="saveModelPolicies('${esc(id)}', this)">Save model policies</button>`;
    }
  } catch (err) {
    msg.className = "form-msg error";
    msg.textContent = err.message;
  } finally {
    btn.disabled = false; btn.textContent = "Sync Models";
  }
}

async function saveModelPolicies(providerId, btn) {
  const rows = document.querySelectorAll(`[data-gov]`);
  btn.disabled = true;
  for (const select of rows) {
    const modelId = select.dataset.gov.split(":").slice(1).join(":");
    const governanceTag = select.value;
    const approvedCheckbox = document.querySelector(`[data-approve="${CSS.escape(select.dataset.gov)}"]`);
    try {
      await api(`/api/admin/providers/${encodeURIComponent(providerId)}/models/${encodeURIComponent(modelId)}`, {
        method: "PUT",
        body: JSON.stringify({ governance_tag: governanceTag, is_approved: approvedCheckbox?.checked }),
      });
    } catch {}
  }
  btn.disabled = false;
  btn.textContent = "Saved!";
  setTimeout(() => { btn.textContent = "Save model policies"; }, 1500);
}

async function toggleProvider(id, newEnabled, btn) {
  btn.disabled = true;
  try {
    await api(`/api/admin/providers/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify({ enabled: newEnabled }) });
    await loadProviders();
  } finally { btn.disabled = false; }
}

async function deleteProvider(id, name, btn) {
  if (!confirm(`Delete provider "${name}"? This will also delete all its synced models.`)) return;
  btn.disabled = true;
  try {
    await api(`/api/admin/providers/${encodeURIComponent(id)}`, { method: "DELETE" });
    await loadProviders();
  } catch (err) {
    alert(err.message);
    btn.disabled = false;
  }
}

// Add provider form
// Show/hide API key
const _toggleApiKey = $("toggleProviderApiKey");
if (_toggleApiKey) {
  _toggleApiKey.addEventListener("click", () => {
    const inp = $("newProviderApiKey");
    const isHidden = inp.type === "password";
    inp.type = isHidden ? "text" : "password";
    _toggleApiKey.textContent = isHidden ? "Hide" : "Show";
  });
}

if ($("addProviderBtn")) {
  $("addProviderBtn").addEventListener("click", () => {
    const panel = $("addProviderPanel");
    const open = panel.style.display !== "none";
    panel.style.display = open ? "none" : "block";
    $("addProviderBtn").textContent = open ? "+ Add Provider" : "✕ Close";
  });
}
if ($("cancelProviderBtn")) {
  $("cancelProviderBtn").addEventListener("click", () => {
    $("addProviderPanel").style.display = "none";
    $("addProviderBtn").textContent = "+ Add Provider";
  });
}
if ($("newProviderType")) {
  $("newProviderType").addEventListener("change", () => {
    const wrap = $("newProviderBaseUrlWrap");
    wrap.style.display = $("newProviderType").value === "anthropic" ? "none" : "block";
  });
}
if ($("saveProviderBtn")) {
  $("saveProviderBtn").addEventListener("click", async () => {
    const msg = $("providerFormMsg");
    msg.className = "form-msg";
    msg.textContent = "";
    const name = $("newProviderName").value.trim();
    const type = $("newProviderType").value;
    const base_url = $("newProviderBaseUrl").value.trim();
    const api_key = $("newProviderApiKey").value.trim();
    if (!name || !api_key) { msg.className = "form-msg error"; msg.textContent = "Name and API key required."; return; }
    try {
      await api("/api/admin/providers", { method: "POST", body: JSON.stringify({ name, type, base_url, api_key }) });
      $("addProviderPanel").style.display = "none";
      $("addProviderBtn").textContent = "+ Add Provider";
      $("newProviderName").value = "";
      $("newProviderApiKey").value = "";
      $("newProviderBaseUrl").value = "";
      await loadProviders();
    } catch (err) {
      msg.className = "form-msg error";
      msg.textContent = err.message;
    }
  });
}

// === ROUTER CONFIG ===
function updateWeightSum() {
  const s = parseFloat($("speedWeight").value);
  const q = parseFloat($("qualityWeight").value);
  const p = parseFloat($("privacyWeight").value);
  const sum = Math.round((s + q + p) * 10) / 10;
  const label = $("weightSumLabel");
  if (label) { label.textContent = `(sum=${sum})`; label.style.color = Math.abs(sum - 1.0) < 0.01 ? "var(--green-deep)" : "var(--danger)"; }
  if ($("speedWeightVal")) $("speedWeightVal").textContent = s.toFixed(1);
  if ($("qualityWeightVal")) $("qualityWeightVal").textContent = q.toFixed(1);
  if ($("privacyWeightVal")) $("privacyWeightVal").textContent = p.toFixed(1);
}

["speedWeight", "qualityWeight", "privacyWeight"].forEach(id => {
  const el = $(id);
  if (el) el.addEventListener("input", updateWeightSum);
});

function renderRouterConfig() {
  const s = state.settings;
  if (!s) return;
  const w = s.routerWeights || { speed: 0.3, quality: 0.5, privacy: 0.2 };
  if ($("speedWeight")) { $("speedWeight").value = w.speed; $("speedWeightVal").textContent = Number(w.speed).toFixed(1); }
  if ($("qualityWeight")) { $("qualityWeight").value = w.quality; $("qualityWeightVal").textContent = Number(w.quality).toFixed(1); }
  if ($("privacyWeight")) { $("privacyWeight").value = w.privacy; $("privacyWeightVal").textContent = Number(w.privacy).toFixed(1); }
  updateWeightSum();
  if ($("sensitivePatterns")) $("sensitivePatterns").value = (s.sensitivePatterns || []).join("\n");
  const modes = new Set(s.localOnlyModes || ["build", "fix"]);
  document.querySelectorAll("#localOnlyModeChecks input[type=checkbox]").forEach(cb => {
    cb.checked = modes.has(cb.value);
  });
}

if ($("saveRouterConfigBtn")) {
  $("saveRouterConfigBtn").addEventListener("click", async () => {
    const msg = $("routerConfigMsg");
    msg.className = "form-msg";
    msg.textContent = "";
    const speed = parseFloat($("speedWeight").value);
    const quality = parseFloat($("qualityWeight").value);
    const privacy = parseFloat($("privacyWeight").value);
    const sum = Math.round((speed + quality + privacy) * 10) / 10;
    if (Math.abs(sum - 1.0) > 0.01) {
      msg.className = "form-msg error"; msg.textContent = `Weights must sum to 1.0 (current: ${sum}).`; return;
    }
    const patternsRaw = $("sensitivePatterns").value.trim();
    const sensitivePatterns = patternsRaw ? patternsRaw.split("\n").map(l => l.trim()).filter(Boolean) : [];
    const localOnlyModes = Array.from(document.querySelectorAll("#localOnlyModeChecks input:checked")).map(cb => cb.value);
    try {
      await api("/api/admin/settings", { method: "POST", body: JSON.stringify({
        routerWeights: { speed, quality, privacy },
        sensitivePatterns,
        localOnlyModes,
      }) });
      msg.className = "form-msg success"; msg.textContent = "Router config saved.";
      await loadState();
    } catch (err) {
      msg.className = "form-msg error"; msg.textContent = err.message;
    }
  });
}

// ═══════════════════════════════════════════════════════════════════
//  REPORTS
// ═══════════════════════════════════════════════════════════════════

// Chart.js global defaults
if (typeof Chart !== 'undefined') {
  Chart.defaults.font.family = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.color = '#888';
  Chart.defaults.animation.duration = 600;
  Chart.defaults.animation.easing = 'easeInOutQuart';
  Chart.defaults.plugins.tooltip.cornerRadius = 10;
  Chart.defaults.plugins.tooltip.padding = 12;
}

const BRAND = {
  yellow:   "#f0d74b",
  yellowDk: "#e8c520",
  ink:      "#1a1a0e",
  cream:    "#fefcec",
  pale:     "#fbf3c8",
  muted:    "#aaa",
  green:    "#4caf50",
  red:      "#ef5350",
  blue:     "#42a5f5",
  orange:   "#ffa726",
  purple:   "#ab47bc",
  teal:     "#26a69a",
  pink:     "#ec407a",
  indigo:   "#5c6bc0",
  lime:     "#9ccc65",
  amber:    "#ffca28",
};

const PALETTE = [
  BRAND.yellow, BRAND.teal, BRAND.blue, BRAND.orange,
  BRAND.purple, BRAND.green, BRAND.pink, BRAND.indigo,
  BRAND.lime, BRAND.amber,
];

// Global chart registry so we can destroy before re-creating
const _charts = {};

function mkChart(id, type, data, opts = {}) {
  const canvas = document.getElementById(id);
  if (!canvas) return;
  if (_charts[id]) { _charts[id].destroy(); delete _charts[id]; }
  const ctx = canvas.getContext("2d");

  // Apply line chart dataset enhancements
  if (type === "line" && data.datasets) {
    data.datasets.forEach(ds => {
      ds.tension = ds.tension ?? 0.4;
      ds.pointRadius = ds.pointRadius ?? 4;
      ds.pointHoverRadius = ds.pointHoverRadius ?? 6;
      ds.pointBackgroundColor = ds.pointBackgroundColor ?? '#fff';
      ds.pointBorderWidth = ds.pointBorderWidth ?? 2;
      if (ds.fill === undefined) ds.fill = true;
    });
  }
  // For mixed charts with line type datasets
  if (type === "bar" && data.datasets) {
    data.datasets.forEach(ds => {
      if (ds.type === "line") {
        ds.tension = ds.tension ?? 0.4;
        ds.pointRadius = ds.pointRadius ?? 4;
        ds.pointHoverRadius = ds.pointHoverRadius ?? 6;
        ds.pointBackgroundColor = ds.pointBackgroundColor ?? '#fff';
        ds.pointBorderWidth = ds.pointBorderWidth ?? 2;
      }
    });
  }

  const isDonut = type === "doughnut" || type === "pie";

  _charts[id] = new Chart(ctx, {
    type,
    data,
    options: {
      responsive: true,
      maintainAspectRatio: true,
      animation: { duration: 600, easing: 'easeInOutQuart',
        ...(isDonut ? { animateRotate: true, animateScale: true } : {}) },
      elements: isDonut ? { arc: { borderWidth: 2 } } : {},
      plugins: {
        legend: {
          display: true,
          labels: { color: BRAND.ink, font: { family: "inherit", size: 11 }, boxWidth: 12, padding: 14 },
        },
        tooltip: {
          backgroundColor: BRAND.ink,
          titleColor: "#fff",
          bodyColor: "#ffffffcc",
          cornerRadius: 10,
          padding: 12,
        },
      },
      scales: type === "bar" || type === "line" ? {
        x: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c820" }, border: { display: false } },
        y: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c840" }, border: { display: false } },
      } : undefined,
      ...opts,
    },
  });
}

function fmt(n) { return Number(n || 0).toLocaleString(); }
function ms(n)  { return n > 0 ? (n >= 1000 ? (n/1000).toFixed(1)+"s" : Math.round(n)+"ms") : "—"; }

function renderKpis(s) {
  const kpis = [
    { label: "Total Users", value: fmt(s.total_users), icon: "👤" },
    { label: "Chat Sessions", value: fmt(s.total_sessions), icon: "💬" },
    { label: "Messages", value: fmt(s.total_messages), icon: "✉️" },
    { label: "Tokens Used", value: fmt(s.total_tokens), icon: "🔢" },
    { label: "Avg Latency", value: ms(s.avg_latency), icon: "⚡" },
  ];
  $("reportKpis").innerHTML = kpis.map(k => `
    <div style="background:#fff;border:1px solid var(--line-soft);border-radius:20px;padding:18px 20px;text-align:center;">
      <div style="font-size:24px;margin-bottom:6px;">${k.icon}</div>
      <div style="font-size:22px;font-weight:700;color:var(--ink);letter-spacing:-.02em;">${k.value}</div>
      <div style="font-size:11px;color:#aaa;text-transform:uppercase;letter-spacing:.07em;margin-top:3px;">${k.label}</div>
    </div>
  `).join("");
}

let _lbPage = 0;
let _lbData = [];

function renderLeaderboard(rows) {
  _lbData = rows;
  _lbPage = 0;
  renderLeaderboardPage();
}

function renderLeaderboardPage() {
  const PAGE = 10;
  const rows = _lbData;
  const total = rows.length;
  const totalPages = Math.ceil(total / PAGE) || 1;
  const start = _lbPage * PAGE;
  const pageRows = rows.slice(start, start + PAGE);
  const medals = ["🥇","🥈","🥉"];

  if (!total) {
    $("reportLeaderboard").innerHTML = `<p style="color:#aaa;text-align:center;padding:24px;">No data yet — start chatting!</p>`;
    return;
  }

  const tableHtml = `
    <table style="width:100%;border-collapse:collapse;">
      <thead>
        <tr style="font-size:10px;text-transform:uppercase;letter-spacing:.07em;color:#aaa;border-bottom:1px solid var(--line-soft);">
          <th style="padding:8px 12px;text-align:left;">#</th>
          <th style="padding:8px 12px;text-align:left;">Employee</th>
          <th style="padding:8px 12px;text-align:left;">Tier</th>
          <th style="padding:8px 12px;text-align:right;">Sessions</th>
          <th style="padding:8px 12px;text-align:right;">Messages</th>
          <th style="padding:8px 12px;text-align:right;">Total Tokens</th>
          <th style="padding:8px 12px;text-align:right;">Avg/Msg</th>
          <th style="padding:8px 12px;text-align:right;">% of Limit</th>
          <th style="padding:8px 12px;text-align:right;">Last Active</th>
        </tr>
      </thead>
      <tbody>
        ${pageRows.map((u, i) => {
          const absIdx = start + i;
          const pct = u.daily_token_limit > 0 ? Math.min(100, Math.round(u.total_tokens / u.daily_token_limit * 100)) : 0;
          const barColor = pct > 90 ? BRAND.red : pct > 60 ? BRAND.orange : BRAND.green;
          const rank = medals[absIdx] || `<span style="color:#aaa;">${absIdx+1}</span>`;
          const av = (u.name || "?").split(" ").map(p => p[0]).slice(0,2).join("").toUpperCase();
          const lastActive = u.last_active ? u.last_active.slice(0,10) : "—";
          return `<tr style="border-bottom:1px solid #f5f0e0;transition:background .1s;" onmouseenter="this.style.background='#fef9e0'" onmouseleave="this.style.background=''">
            <td style="padding:12px 12px;font-size:18px;">${rank}</td>
            <td style="padding:12px 12px;">
              <div style="display:flex;align-items:center;gap:10px;">
                <div style="width:34px;height:34px;border-radius:50%;background:${BRAND.yellow};display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:${BRAND.ink};flex-shrink:0;">${esc(av)}</div>
                <div>
                  <div style="font-size:13px;font-weight:500;">${esc(u.name)}</div>
                  <div style="font-size:11px;color:#aaa;">${esc(u.email)}</div>
                </div>
              </div>
            </td>
            <td style="padding:12px 12px;"><span class="badge badge-blue" style="font-size:11px;">${esc(u.ai_access_tier||"standard")}</span></td>
            <td style="padding:12px 12px;text-align:right;font-size:13px;">${fmt(u.sessions)}</td>
            <td style="padding:12px 12px;text-align:right;font-size:13px;">${fmt(u.messages)}</td>
            <td style="padding:12px 12px;text-align:right;font-size:14px;font-weight:600;">${fmt(u.total_tokens)}</td>
            <td style="padding:12px 12px;text-align:right;font-size:12px;color:#666;">${fmt(Math.round(u.avg_tokens_per_msg))}</td>
            <td style="padding:12px 12px;text-align:right;">
              <div style="display:flex;align-items:center;gap:6px;justify-content:flex-end;">
                <div style="width:60px;height:5px;background:#e5e0c8;border-radius:3px;overflow:hidden;">
                  <div style="width:${pct}%;height:100%;background:${barColor};border-radius:3px;"></div>
                </div>
                <span style="font-size:11px;color:#888;min-width:30px;">${pct}%</span>
              </div>
            </td>
            <td style="padding:12px 12px;text-align:right;font-size:11px;color:#aaa;">${lastActive}</td>
          </tr>`;
        }).join("")}
      </tbody>
    </table>
  `;

  const paginationHtml = totalPages > 1 ? `
    <div style="display:flex;align-items:center;justify-content:space-between;padding:14px 12px;border-top:1px solid #f0ead8;margin-top:4px;">
      <span style="font-size:12px;color:#888;">Showing ${start+1}–${Math.min(start+PAGE,total)} of ${total} employees</span>
      <div style="display:flex;gap:6px;">
        <button onclick="lbPrev()" ${_lbPage===0?'disabled':''} style="padding:6px 14px;border-radius:8px;border:1.5px solid #e5e0c8;background:${_lbPage===0?'#f5f0e0':'#fff'};cursor:${_lbPage===0?'not-allowed':'pointer'};font-size:13px;color:${_lbPage===0?'#ccc':'#1a1a0e'};">← Prev</button>
        ${Array.from({length:totalPages},(_,i)=>`<button onclick="lbGoTo(${i})" style="padding:6px 12px;border-radius:8px;border:1.5px solid ${i===_lbPage?'#1a1a0e':'#e5e0c8'};background:${i===_lbPage?'#1a1a0e':'#fff'};color:${i===_lbPage?'#fff':'#1a1a0e'};font-size:13px;cursor:pointer;">${i+1}</button>`).join("")}
        <button onclick="lbNext()" ${_lbPage>=totalPages-1?'disabled':''} style="padding:6px 14px;border-radius:8px;border:1.5px solid #e5e0c8;background:${_lbPage>=totalPages-1?'#f5f0e0':'#fff'};cursor:${_lbPage>=totalPages-1?'not-allowed':'pointer'};font-size:13px;color:${_lbPage>=totalPages-1?'#ccc':'#1a1a0e'};">Next →</button>
      </div>
    </div>
  ` : "";

  $("reportLeaderboard").innerHTML = tableHtml + paginationHtml;
}

window.lbPrev = function() { if(_lbPage>0){_lbPage--;renderLeaderboardPage();} };
window.lbNext = function() { if(_lbPage<Math.ceil(_lbData.length/10)-1){_lbPage++;renderLeaderboardPage();} };
window.lbGoTo = function(p) { _lbPage=p; renderLeaderboardPage(); };

function renderModelTable(rows) {
  if (!rows.length) { $("reportModelTable").innerHTML = `<p style="color:#aaa;text-align:center;padding:20px;">No model data yet.</p>`; return; }
  $("reportModelTable").innerHTML = `
    <table style="width:100%;border-collapse:collapse;">
      <thead>
        <tr style="font-size:10px;text-transform:uppercase;letter-spacing:.07em;color:#aaa;border-bottom:1px solid var(--line-soft);">
          <th style="padding:8px 12px;text-align:left;">Model</th>
          <th style="padding:8px 12px;text-align:right;">Uses</th>
          <th style="padding:8px 12px;text-align:right;">Total Tokens</th>
          <th style="padding:8px 12px;text-align:right;">Avg Latency</th>
          <th style="padding:8px 12px;text-align:left;">Usage bar</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map((m, i) => {
          const maxUses = rows[0].uses || 1;
          const pct = Math.round(m.uses / maxUses * 100);
          return `<tr style="border-bottom:1px solid #f5f0e0;" onmouseenter="this.style.background='#fef9e0'" onmouseleave="this.style.background=''">
            <td style="padding:10px 12px;font-weight:500;">${esc(m.model_name||"—")}</td>
            <td style="padding:10px 12px;text-align:right;">${fmt(m.uses)}</td>
            <td style="padding:10px 12px;text-align:right;">${fmt(m.total_tokens)}</td>
            <td style="padding:10px 12px;text-align:right;">${ms(m.avg_latency)}</td>
            <td style="padding:10px 12px;">
              <div style="display:flex;align-items:center;gap:8px;">
                <div style="flex:1;height:6px;background:#e5e0c8;border-radius:3px;overflow:hidden;">
                  <div style="width:${pct}%;height:100%;background:${PALETTE[i%PALETTE.length]};border-radius:3px;"></div>
                </div>
                <span style="font-size:11px;color:#888;min-width:32px;">${pct}%</span>
              </div>
            </td>
          </tr>`;
        }).join("")}
      </tbody>
    </table>
  `;
}

async function loadReports() {
  const days = $("reportPeriod") ? $("reportPeriod").value : 30;
  let d;
  try {
    d = await api(`/api/admin/reports?days=${days}`);
  } catch(e) {
    console.error("Reports load failed:", e);
    return;
  }

  // KPIs
  renderKpis(d.summary || {});

  // 1. Daily Messages & Tokens — dual-axis bar+line
  const labels1 = d.dailyActivity.map(r => r.day.slice(5)); // MM-DD
  mkChart("chartDailyActivity", "bar", {
    labels: labels1,
    datasets: [
      {
        label: "Messages",
        data: d.dailyActivity.map(r => r.messages),
        backgroundColor: 'rgba(240,215,75,0.85)',
        borderColor: '#e8c520',
        borderWidth: 0,
        borderRadius: 6,
        yAxisID: "y",
      },
      {
        label: "Tokens",
        type: "line",
        data: d.dailyActivity.map(r => r.tokens),
        borderColor: '#26a69a',
        backgroundColor: 'rgba(38,166,154,0.08)',
        borderWidth: 2.5,
        pointRadius: 4,
        pointBackgroundColor: '#fff',
        pointBorderColor: '#26a69a',
        pointBorderWidth: 2,
        tension: 0.5,
        fill: true,
        yAxisID: "y1",
      },
    ],
  }, {
    scales: {
      x: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c820" } },
      y: { position: "left", ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c840" }, title: { display: true, text: "Messages", color: BRAND.muted, font: { size: 10 } } },
      y1: { position: "right", ticks: { color: BRAND.teal, font: { size: 10 } }, grid: { drawOnChartArea: false }, title: { display: true, text: "Tokens", color: BRAND.teal, font: { size: 10 } } },
    },
  });

  // 2. Model usage — donut
  const mu = d.modelUsage.slice(0,8);
  mkChart("chartModelUsage", "doughnut", {
    labels: mu.map(r => r.model_name || "?"),
    datasets: [{ data: mu.map(r => r.uses), backgroundColor: PALETTE, borderColor: "#fff", borderWidth: 2, hoverOffset: 6 }],
  }, { cutout: "62%", plugins: { legend: { position: "bottom", labels: { color: BRAND.ink, font: { size: 10 }, boxWidth: 10, padding: 8 } } } });

  // 3. Mode breakdown — donut
  const mb = d.modeBreakdown;
  mkChart("chartModeBreakdown", "doughnut", {
    labels: mb.map(r => r.mode || "ask"),
    datasets: [{ data: mb.map(r => r.count), backgroundColor: PALETTE.slice(2), borderColor: "#fff", borderWidth: 2, hoverOffset: 6 }],
  }, { cutout: "55%", plugins: { legend: { position: "bottom", labels: { color: BRAND.ink, font: { size: 11 }, boxWidth: 10 } } } });

  // 4. Live vs Failed — pie
  const lf = d.liveVsFailed;
  mkChart("chartLiveVsFailed", "doughnut", {
    labels: ["Successful", "Failed"],
    datasets: [{ data: [lf.live_count||0, lf.failed_count||0], backgroundColor: [BRAND.green, BRAND.red], borderColor: "#fff", borderWidth: 3, hoverOffset: 6 }],
  }, { cutout: "55%", plugins: { legend: { position: "bottom", labels: { color: BRAND.ink, font: { size: 11 }, boxWidth: 10 } } } });

  // 5. Tier distribution — pie
  const td = d.tierDist;
  mkChart("chartTierDist", "doughnut", {
    labels: td.map(r => r.tier || "standard"),
    datasets: [{ data: td.map(r => r.count), backgroundColor: [BRAND.yellow, BRAND.blue, BRAND.purple, BRAND.teal], borderColor: "#fff", borderWidth: 2, hoverOffset: 6 }],
  }, { cutout: "55%", plugins: { legend: { position: "bottom", labels: { color: BRAND.ink, font: { size: 11 }, boxWidth: 10 } } } });

  // 6. Department token usage — horizontal bar
  const du = d.deptUsage.filter(r => r.dept);
  mkChart("chartDeptUsage", "bar", {
    labels: du.map(r => r.dept),
    datasets: [
      { label: "Tokens", data: du.map(r => r.tokens), backgroundColor: BRAND.yellow + "cc", borderColor: BRAND.yellowDk, borderWidth: 1, borderRadius: 4 },
      { label: "Sessions", data: du.map(r => r.sessions), backgroundColor: BRAND.teal + "99", borderColor: BRAND.teal, borderWidth: 1, borderRadius: 4 },
    ],
  }, { indexAxis: "y", scales: { x: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c840" } }, y: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c820" } } } });

  // 7. Response latency — horizontal bar
  const lat = d.latencyByModel;
  mkChart("chartLatency", "bar", {
    labels: lat.map(r => (r.model_name||"?").slice(0,18)),
    datasets: [{ label: "Avg Latency (ms)", data: lat.map(r => r.avg_ms), backgroundColor: PALETTE, borderWidth: 0, borderRadius: 4 }],
  }, { indexAxis: "y", plugins: { legend: { display: false } }, scales: { x: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c840" } }, y: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { display: false } } } });

  // 8. Audit timeline — line
  const at = d.auditTimeline;
  mkChart("chartAuditTimeline", "line", {
    labels: at.map(r => r.day.slice(5)),
    datasets: [{ label: "Audit Events", data: at.map(r => r.events), borderColor: BRAND.orange, backgroundColor: BRAND.orange + "25", borderWidth: 2, pointRadius: 3, tension: 0.4, fill: true }],
  });

  // 9. Audit action breakdown — horizontal bar
  const ab = d.auditBreakdown;
  mkChart("chartAuditBreakdown", "bar", {
    labels: ab.map(r => r.action.replace(/\./g, " ")),
    datasets: [{ label: "Count", data: ab.map(r => r.count), backgroundColor: BRAND.indigo + "cc", borderColor: BRAND.indigo, borderWidth: 1, borderRadius: 4 }],
  }, { indexAxis: "y", plugins: { legend: { display: false } }, scales: { x: { ticks: { color: BRAND.muted, font: { size: 10 } }, grid: { color: "#e5e0c840" } }, y: { ticks: { color: BRAND.muted, font: { size: 9 } }, grid: { display: false } } } });

  // Leaderboard & model table
  renderLeaderboard(d.tokenLeaderboard);
  renderModelTable(d.modelUsage);
}

if ($("refreshReportsBtn")) {
  $("refreshReportsBtn").addEventListener("click", loadReports);
}
if ($("reportPeriod")) {
  $("reportPeriod").addEventListener("change", loadReports);
}

// Load reports when switching to the tab
const _origSwitchTabForReports = switchTab;
// We can't wrap switchTab (hoisting issue), so we listen on nav items
document.querySelectorAll(".nav-item[data-tab]").forEach(btn => {
  if (btn.dataset.tab === "reports") {
    btn.addEventListener("click", function() { setTimeout(loadReports, 50); }, true);
  }
});

loadState().then(checkOllama);
/* Re-check Ollama every 30 seconds so status stays current without a page reload */
setInterval(checkOllama, 30000);
