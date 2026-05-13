let state = null;
let activeMode = "ask";

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (response.status === 401) {
    window.location.href = "/login";
    return null;
  }
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed");
  return data;
}

function allowedModels() {
  return state.models.filter((model) => {
    if (!state.allowedModelIds.includes(model.id) || model.status === "disabled") return false;
    if (model.provider === "api" && !state.settings.allowApiModels) return false;
    return true;
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderMessages() {
  const messages = $("#messages");
  const chat = state.chats.find((item) => item.userId === state.activeUser.id) || state.chats[0];
  const items = chat?.messages || [];
  messages.innerHTML = items
    .map((message) => {
      const meta = message.role === "assistant"
        ? `${message.modelName || "Assistant"}${message.live === false ? " · setup needed" : ""}`
        : `${state.activeUser.name}${message.mode ? ` · ${message.mode}` : ""}`;
      return `<article class="message ${message.role}">
        <div class="message-meta">${escapeHtml(meta)}</div>
        <div class="message-content">${escapeHtml(message.content)}</div>
      </article>`;
    })
    .join("");
  messages.scrollTop = messages.scrollHeight;
}

function renderAccess() {
  const department = state.departments.find((item) => item.id === state.activeUser.departmentId);
  $("#userSubtitle").textContent = `${state.activeUser.name} · ${department?.name || "Workspace"}`;
  $("#policySummary").innerHTML = `<p class="muted"><strong>${department?.name || "General"}</strong><br>Model access comes from your user, group, and department grants.</p>
    <div class="tag-list">${allowedModels().map((model) => `<span>${escapeHtml(model.name)}</span>`).join("")}</div>`;
  $("#manualModel").innerHTML = `<option value="">Auto Router</option>` + allowedModels().map((model) => `<option value="${model.id}">${escapeHtml(model.name)}</option>`).join("");
  $("#adminLink").style.display = state.activeUser.role === "admin" ? "inline-flex" : "none";
}

function renderAll() {
  renderMessages();
  renderAccess();
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
  $$(".mode").forEach((button) => {
    button.addEventListener("click", () => {
      activeMode = button.dataset.mode;
      $$(".mode").forEach((item) => item.classList.remove("active"));
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
      $("#routerDecision").innerHTML = `<p class="muted"><strong>${escapeHtml(result.model.name)}</strong><br>${escapeHtml(result.route.reason)}<br>Detected: ${escapeHtml(result.route.tags.join(", "))}</p>`;
      await loadState();
    } catch (error) {
      $("#routerDecision").innerHTML = `<p class="muted"><strong>Request failed</strong><br>${escapeHtml(error.message)}</p>`;
    }
  });

  $("#clearChat").addEventListener("click", async () => {
    await api("/api/chat/clear", { method: "POST", body: "{}" });
    $("#routerDecision").textContent = "Send a request to see routing logic.";
    await loadState();
  });

  $("#logout").addEventListener("click", async () => {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
  });
}

bindEvents();
loadState().then(checkOllama);
