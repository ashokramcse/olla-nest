let state = null;

const $ = (selector) => document.querySelector(selector);

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (response.status === 401) {
    window.location.href = "/login";
    return null;
  }
  if (response.status === 403) {
    window.location.href = "/app";
    return null;
  }
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed");
  return data;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderModels() {
  $("#modelTable").innerHTML = `
    <div class="table-row header"><div>Model</div><div>Provider</div><div>Status</div><div>Capabilities</div></div>
    ${state.models
      .filter((model) => model.provider === "ollama")
      .map((model) => `<div class="table-row">
        <div><strong>${escapeHtml(model.name)}</strong><br><span class="muted">${escapeHtml(model.model)}</span></div>
        <div><span class="badge">${escapeHtml(model.provider)}</span></div>
        <div><span class="badge">${escapeHtml(model.status)}</span></div>
        <div class="muted">${escapeHtml((model.capabilities || []).join(", "))}</div>
      </div>`)
      .join("")}
  `;
}

function renderUsers() {
  $("#userList").innerHTML = state.users
    .map((user) => {
      const dept = state.departments.find((item) => item.id === user.departmentId);
      return `<div class="user-card"><strong>${escapeHtml(user.name)}</strong><span>${escapeHtml(user.email || "")}<br>${escapeHtml(user.role)} · ${escapeHtml(dept?.name || "No department")}</span></div>`;
    })
    .join("");
}

function renderAccess() {
  $("#policyList").innerHTML = [
    `<div class="policy-card"><strong>User grants</strong><span>Direct access for specific employees.</span></div>`,
    `<div class="policy-card"><strong>Group grants</strong><span>Shared access for teams and roles.</span></div>`,
    `<div class="policy-card"><strong>Department grants</strong><span>Company departments can receive model access independently.</span></div>`,
    `<div class="policy-card"><strong>Production storage</strong><span>SQL: ${state.dbConfig.recommendedProduction.sql.provider}<br>NoSQL: ${state.dbConfig.recommendedProduction.document.provider}<br>Realtime: ${state.dbConfig.recommendedProduction.realtime.provider}</span></div>`,
    `<div class="policy-card"><strong>Local fallback</strong><span>SQL: ${state.dbConfig.localDevelopment.sql.provider}<br>Document: ${state.dbConfig.localDevelopment.document.provider}<br>Realtime: ${state.dbConfig.localDevelopment.realtime.provider}</span></div>`,
  ].join("");
}

function renderSettings() {
  $("#routerEnabled").checked = state.settings.routerEnabled;
  $("#allowApiModels").checked = state.settings.allowApiModels;
  $("#localOnlyDefault").checked = state.settings.localOnlyDefault;
}

function renderAudit() {
  $("#auditList").innerHTML = state.audit.length
    ? state.audit.map((item) => `<div class="audit-card">${escapeHtml(item.actor)}: ${escapeHtml(item.detail)}</div>`).join("")
    : `<div class="muted">No audit events yet.</div>`;
}

function renderMetrics() {
  $("#modelCount").textContent = state.models.filter((model) => model.provider === "ollama" && model.status === "available").length;
  $("#userCount").textContent = state.users.length;
  $("#groupCount").textContent = state.groups.length;
  $("#departmentCount").textContent = state.departments.length;
  $("#adminIdentity").innerHTML = `<div class="mini-item"><span>${escapeHtml(state.activeUser.name)}</span><span>${escapeHtml(state.activeUser.role)}</span></div>`;
}

function renderAll() {
  renderMetrics();
  renderModels();
  renderUsers();
  renderAccess();
  renderSettings();
  renderAudit();
}

async function loadState() {
  state = await api("/api/state");
  if (!state) return;
  renderAll();
}

async function checkOllama() {
  const status = $("#ollamaStatus");
  const data = await api("/api/ollama/models");
  if (!data) return;
  if (data.ok) {
    status.textContent = `${data.models.length} local model${data.models.length === 1 ? "" : "s"} available`;
    status.className = "status-pill ok";
  } else {
    status.textContent = "Ollama not connected";
    status.className = "status-pill off";
  }
}

function bindEvents() {
  $("#syncOllama").addEventListener("click", async () => {
    await checkOllama();
    await loadState();
  });

  $("#saveSettings").addEventListener("click", async () => {
    await api("/api/admin/settings", {
      method: "POST",
      body: JSON.stringify({
        routerEnabled: $("#routerEnabled").checked,
        allowApiModels: $("#allowApiModels").checked,
        localOnlyDefault: $("#localOnlyDefault").checked,
      }),
    });
    await loadState();
  });

  $("#logout").addEventListener("click", async () => {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
  });
}

bindEvents();
loadState().then(checkOllama);
