let state = null;
let activeMode = "ask";

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed");
  return data;
}

function currentPolicy() {
  const dept = state.departments.find((d) => d.id === state.activeUser.departmentId);
  return state.policies.find((p) => p.id === dept?.policyId);
}

function allowedModels() {
  return state.models.filter((m) => {
    if (!state.allowedModelIds.includes(m.id) || m.status === "disabled") return false;
    if (m.provider === "api" && !state.settings.allowApiModels) return false;
    return true;
  });
}

function renderUsers() {
  const select = $("#userSwitch");
  select.innerHTML = state.users
    .map((user) => `<option value="${user.id}" ${user.id === state.activeUser.id ? "selected" : ""}>${user.name} (${user.role})</option>`)
    .join("");
}

function renderApprovedModels() {
  const list = $("#approvedModels");
  const models = allowedModels();
  list.innerHTML = models.length
    ? models.map((m) => `<div class="mini-item"><span>${m.name}</span><span>${m.privacy}</span></div>`).join("")
    : `<div class="muted">No active models approved.</div>`;

  const manual = $("#manualModel");
  manual.innerHTML = `<option value="">Auto Router</option>` + models.map((m) => `<option value="${m.id}">${m.name}</option>`).join("");
}

function renderMessages() {
  const messages = $("#messages");
  const chat = state.chats.find((c) => c.userId === state.activeUser.id) || state.chats[0];
  const items = chat?.messages || [];
  messages.innerHTML = items
    .map((message) => {
      const meta = message.role === "assistant"
        ? `${message.modelName || "Assistant"}${message.live === false ? " · setup needed" : ""}`
        : `${state.activeUser.name}${message.mode ? ` · ${message.mode}` : ""}`;
      return `<article class="message ${message.role}">
        <div class="message-meta">${meta}</div>
        <div class="message-content">${escapeHtml(message.content)}</div>
      </article>`;
    })
    .join("");
  messages.scrollTop = messages.scrollHeight;
}

function renderPolicySummary() {
  const policy = currentPolicy();
  $("#policySummary").innerHTML = policy
    ? `<p class="muted"><strong>${policy.name}</strong><br>${policy.description}</p>
       <div class="tag-list">${policy.allowedModes.map((m) => `<span>${m}</span>`).join("")}</div>`
    : `<p class="muted">No department policy assigned.</p>`;
}

function renderAdmin() {
  $("#routerEnabled").checked = state.settings.routerEnabled;
  $("#allowApiModels").checked = state.settings.allowApiModels;
  $("#localOnlyDefault").checked = state.settings.localOnlyDefault;

  $("#modelTable").innerHTML = `
    <div class="table-row header"><div>Model</div><div>Provider</div><div>Status</div><div>Strengths</div></div>
    ${state.models.map((m) => `<div class="table-row">
      <div><strong>${m.name}</strong><br><span class="muted">${m.model}</span></div>
      <div><span class="badge">${m.provider}</span></div>
      <div><span class="badge">${m.status}</span></div>
      <div class="muted">${m.strengths.join(", ")}</div>
    </div>`).join("")}
  `;

  $("#policyList").innerHTML = state.policies
    .map((p) => {
      const names = p.allowedModelIds
        .map((id) => state.models.find((m) => m.id === id)?.name)
        .filter(Boolean)
        .join(", ");
      return `<div class="policy-card"><strong>${p.name}</strong><span>${p.description}<br>Models: ${names}</span></div>`;
    })
    .join("");

  $("#userList").innerHTML = state.users
    .map((u) => {
      const dept = state.departments.find((d) => d.id === u.departmentId);
      return `<div class="user-card"><strong>${u.name}</strong><span>${u.role} · ${dept?.name || "No department"}</span></div>`;
    })
    .join("");

  $("#auditList").innerHTML = state.audit.length
    ? state.audit.map((a) => `<div class="audit-card">${a.actor}: ${a.detail}</div>`).join("")
    : `<div class="muted">No audit events yet.</div>`;
}

function renderAll() {
  renderUsers();
  renderApprovedModels();
  renderMessages();
  renderPolicySummary();
  renderAdmin();
  const adminButton = document.querySelector('[data-view="admin"]');
  adminButton.style.display = state.activeUser.role === "admin" ? "block" : "none";
  if (state.activeUser.role !== "admin" && $("#adminView").classList.contains("active")) {
    document.querySelector('[data-view="workspace"]').click();
  }
  $("#pageTitle").textContent = state.activeUser.role === "admin" ? "Admin Workspace" : "Employee Workspace";
}

async function loadState() {
  state = await api("/api/state");
  renderAll();
}

async function checkOllama() {
  const status = $("#ollamaStatus");
  const data = await api("/api/ollama/models");
  if (data.ok) {
    const names = new Set(data.models.map((model) => model.name));
    const configured = state.models.filter((model) => model.provider === "ollama" && names.has(model.model)).length;
    status.textContent = `${configured}/${state.models.filter((model) => model.provider === "ollama").length} configured local models ready`;
    status.className = "status-pill ok";
  } else {
    status.textContent = "Ollama not connected";
    status.className = "status-pill off";
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function bindEvents() {
  $("#userSwitch").addEventListener("change", async (event) => {
    await api("/api/switch-user", {
      method: "POST",
      body: JSON.stringify({ userId: event.target.value }),
    });
    await loadState();
  });

  $$(".nav-item").forEach((button) => {
    button.addEventListener("click", () => {
      $$(".nav-item").forEach((x) => x.classList.remove("active"));
      $$(".view").forEach((x) => x.classList.remove("active"));
      button.classList.add("active");
      $(`#${button.dataset.view}View`).classList.add("active");
      $("#pageTitle").textContent = button.textContent;
    });
  });

  $$(".mode").forEach((button) => {
    button.addEventListener("click", () => {
      activeMode = button.dataset.mode;
      $$(".mode").forEach((x) => x.classList.remove("active"));
      button.classList.add("active");
    });
  });

  $("#chatForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = $("#messageInput");
    const message = input.value.trim();
    if (!message) return;
    input.value = "";
    $("#routerDecision").textContent = "Routing request...";
    try {
      const result = await api("/api/chat", {
        method: "POST",
        body: JSON.stringify({
          message,
          mode: activeMode,
          manualModelId: $("#manualModel").value || null,
        }),
      });
      $("#routerDecision").innerHTML = `<p class="muted"><strong>${result.model.name}</strong><br>${result.route.reason}<br>Detected: ${result.route.tags.join(", ")}</p>`;
      await loadState();
    } catch (error) {
      $("#routerDecision").innerHTML = `<p class="muted"><strong>Request failed</strong><br>${escapeHtml(error.message)}</p>`;
    }
  });

  $("#syncOllama").addEventListener("click", checkOllama);

  $("#clearChat").addEventListener("click", async () => {
    await api("/api/chat/clear", { method: "POST", body: "{}" });
    $("#routerDecision").textContent = "Send a request to see routing logic.";
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
}

bindEvents();
loadState().then(checkOllama);
