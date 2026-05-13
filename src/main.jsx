import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

const DEFAULT_ADMIN_PASSWORD = "CHANGE_ME_ON_FIRST_BOOT";

function Icon({ name, filled = false, className = "" }) {
  return (
    <span className={`material-symbols-rounded ${filled ? "icon-filled" : ""} ${className}`}>
      {name}
    </span>
  );
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (response.status === 401) {
    window.location.href = "/login";
    return null;
  }
  if (response.status === 403 && window.location.pathname === "/admin") {
    window.location.href = "/app";
    return null;
  }
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed");
  return data;
}

function cx(...classes) {
  return classes.filter(Boolean).join(" ");
}

function Pill({ children, tone = "default" }) {
  return <span className={`pill pill-${tone}`}>{children}</span>;
}

function Button({ children, variant = "primary", className = "", ...props }) {
  return (
    <button className={`btn btn-${variant} ${className}`} {...props}>
      {children}
    </button>
  );
}

function Field({ label, className = "", ...props }) {
  return (
    <label className={`field ${className}`}>
      <span>{label}</span>
      <input {...props} />
    </label>
  );
}

function SelectField({ label, children, className = "", ...props }) {
  return (
    <label className={`field ${className}`}>
      <span>{label}</span>
      <select {...props}>{children}</select>
    </label>
  );
}

function Notice({ children, tone = "info" }) {
  if (!children) return null;
  return <div className={`notice notice-${tone}`}>{children}</div>;
}

function LoadingPage() {
  return (
    <div className="loading-page">
      <div className="loading-card">
        <div className="brand-mark">ON</div>
        <span>Loading Olla Nest</span>
      </div>
    </div>
  );
}

function AuthShell({ children }) {
  return (
    <main className="auth-shell">
      <section className="auth-panel">
        <div className="auth-copy">
          <div className="brand-lockup">
            <div className="brand-mark">ON</div>
            <div>
              <strong>Olla Nest</strong>
              <span>Company AI Workspace</span>
            </div>
          </div>
          <h1>Local AI, controlled by your company.</h1>
          <p>
            A clean workspace for teams to use Ollama models with admin access,
            routing rules, and local-first governance.
          </p>
          <div className="auth-points">
            <span><Icon name="router" /> Auto model routing</span>
            <span><Icon name="admin_panel_settings" /> Admin controlled access</span>
            <span><Icon name="database" /> Polyglot storage ready</span>
          </div>
        </div>
      </section>
      <section className="auth-form-wrap">{children}</section>
    </main>
  );
}

function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");

  useEffect(() => {
    api("/api/auth/me").then((me) => {
      if (me?.authenticated) window.location.href = me.user.role === "admin" ? "/admin" : "/app";
    });
    api("/api/bootstrap").then((boot) => {
      if (!boot) return;
      setEmail(boot.adminEmail);
      setHint(`First boot admin: ${boot.adminEmail}`);
    });
  }, []);

  async function submit(event) {
    event.preventDefault();
    setError("");
    try {
      const result = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      window.location.href = result.redirectTo;
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <AuthShell>
      <form className="login-card" onSubmit={submit}>
        <div className="mobile-brand">
          <div className="brand-mark">ON</div>
          <div>
            <strong>Olla Nest</strong>
            <span>Company AI Workspace</span>
          </div>
        </div>
        <div>
          <p className="eyebrow">Welcome back</p>
          <h2>Sign in</h2>
          <p className="muted">Use your company account to continue.</p>
        </div>
        <Field label="Email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" />
        <Field label="Password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
        <Button type="submit" className="w-full">Sign in</Button>
        <div className="credential-hint">
          <span>Default admin</span>
          <strong>{hint || "Configured on first boot"}</strong>
          <span>Password: {DEFAULT_ADMIN_PASSWORD}</span>
        </div>
        <Notice tone="error">{error}</Notice>
      </form>
    </AuthShell>
  );
}

function useAppState() {
  const [state, setState] = useState(null);
  const [ollama, setOllama] = useState(null);
  const reload = async () => {
    const next = await api("/api/state");
    if (next) setState(next);
  };
  const refreshModels = async () => {
    const next = await api("/api/ollama/models");
    if (next) setOllama(next);
    await reload();
  };
  useEffect(() => {
    reload().then(refreshModels);
  }, []);
  return { state, ollama, reload, refreshModels };
}

function allowedModels(state) {
  return state.models.filter((model) => state.allowedModelIds.includes(model.id) && model.status !== "disabled");
}

function AppFrame({ title, subtitle, active, state, ollama, children, rightSlot }) {
  async function logout() {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
  }

  const nav = [
    { href: "/app", label: "Workspace", icon: "forum", key: "workspace" },
    ...(state?.activeUser?.role === "admin" ? [{ href: "/admin", label: "Admin", icon: "dashboard", key: "admin" }] : []),
  ];

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <div className="brand-lockup">
          <div className="brand-mark">ON</div>
          <div>
            <strong>Olla Nest</strong>
            <span>AI Workspace</span>
          </div>
        </div>
        <nav className="side-nav">
          {nav.map((item) => (
            <a key={item.key} className={cx("side-link", active === item.key && "active")} href={item.href}>
              <Icon name={item.icon} filled={active === item.key} />
              {item.label}
            </a>
          ))}
        </nav>
        <div className="sidebar-card">
          <span>Local-first mode</span>
          <strong>{state?.settings?.localOnlyDefault ? "Enabled" : "Optional"}</strong>
          <p>Company admins decide who can use which model.</p>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div>
            <p className="eyebrow">{subtitle}</p>
            <h1>{title}</h1>
          </div>
          <div className="topbar-actions">
            <div className="search-box">
              <Icon name="search" />
              <span>Search Olla Nest</span>
            </div>
            <Pill tone={ollama?.ok === false ? "warning" : "success"}>
              {ollama?.ok === false ? "Ollama offline" : `${ollama?.models?.length ?? 0} local models`}
            </Pill>
            {rightSlot}
            <Button variant="outline" onClick={logout}>Logout</Button>
          </div>
        </header>
        <main className="main-content">{children}</main>
      </div>
    </div>
  );
}

function WorkspacePage() {
  const { state, ollama, reload } = useAppState();
  const [mode, setMode] = useState("ask");
  const [message, setMessage] = useState("");
  const [manualModelId, setManualModelId] = useState("");
  const [router, setRouter] = useState("Send a message and Olla Nest will pick the best approved model.");
  const [accountMessage, setAccountMessage] = useState("");
  const [passwords, setPasswords] = useState({ currentPassword: "", newPassword: "" });

  if (!state) return <LoadingPage />;

  const models = allowedModels(state);
  const chat = state.chats.find((item) => item.userId === state.activeUser.id) || state.chats[0];
  const department = state.departments.find((item) => item.id === state.activeUser.departmentId);
  const messages = chat?.messages || [];

  async function send(event) {
    event.preventDefault();
    if (!message.trim()) return;
    setRouter("Routing request...");
    const result = await api("/api/chat", {
      method: "POST",
      body: JSON.stringify({ message, mode, manualModelId: manualModelId || null }),
    });
    setMessage("");
    setRouter(`${result.model.name}: ${result.route.reason}`);
    await reload();
  }

  async function changePassword(event) {
    event.preventDefault();
    setAccountMessage("");
    try {
      await api("/api/account/password", { method: "POST", body: JSON.stringify(passwords) });
      setPasswords({ currentPassword: "", newPassword: "" });
      setAccountMessage("Password updated.");
    } catch (err) {
      setAccountMessage(err.message);
    }
  }

  async function clearChat() {
    await api("/api/chat/clear", { method: "POST", body: "{}" });
    await reload();
  }

  return (
    <AppFrame
      title="Workspace"
      subtitle={`${state.activeUser.name} · ${department?.name || "General"}`}
      active="workspace"
      state={state}
      ollama={ollama}
      rightSlot={<Button variant="soft" onClick={clearChat}><Icon name="add" /> New chat</Button>}
    >
      <div className="workspace-grid">
        <section className="chat-card">
          <div className="chat-toolbar">
            <div className="mode-tabs">
              {["ask", "build", "review", "fix", "learn"].map((item) => (
                <button key={item} className={cx(mode === item && "active")} onClick={() => setMode(item)} type="button">
                  {item}
                </button>
              ))}
            </div>
          </div>
          <div className="chat-scroll">
            {messages.length ? (
              messages.map((item, index) => (
                <div key={`${item.role}-${index}`} className={cx("message-row", item.role === "user" && "from-user")}>
                  <div className={cx("message-bubble", item.role === "user" && "user-bubble")}>
                    <span>{item.role === "assistant" ? item.modelName || "Assistant" : `${state.activeUser.name} · ${item.mode || mode}`}</span>
                    <p>{item.content}</p>
                  </div>
                </div>
              ))
            ) : (
              <div className="empty-chat">
                <Icon name="auto_awesome" />
                <h2>Ask anything your approved models can handle.</h2>
                <p>The router will choose from models allowed for your user, group, and department.</p>
              </div>
            )}
          </div>
          <form className="composer" onSubmit={send}>
            <textarea
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              placeholder="Message Olla Nest..."
              rows={4}
            />
            <div className="composer-actions">
              <select value={manualModelId} onChange={(event) => setManualModelId(event.target.value)}>
                <option value="">Auto Router</option>
                {models.map((model) => <option key={model.id} value={model.id}>{model.name}</option>)}
              </select>
              <Button type="submit"><Icon name="send" /> Send</Button>
            </div>
          </form>
        </section>
        <aside className="workspace-panel">
          <div className="panel-card">
            <div className="panel-heading">
              <Icon name="verified_user" />
              <div>
                <h3>Access</h3>
                <p>{department?.name || "General"} department</p>
              </div>
            </div>
            <div className="tag-list">
              {models.length ? models.map((model) => <span key={model.id}>{model.name}</span>) : <span>No approved local models</span>}
            </div>
          </div>
          <div className="panel-card">
            <div className="panel-heading">
              <Icon name="route" />
              <div>
                <h3>Router</h3>
                <p>Latest routing decision</p>
              </div>
            </div>
            <p className="panel-copy">{router}</p>
          </div>
          <form className="panel-card" onSubmit={changePassword}>
            <div className="panel-heading">
              <Icon name="account_circle" />
              <div>
                <h3>Account</h3>
                <p>{state.activeUser.email}</p>
              </div>
            </div>
            <Field label="Current password" type="password" value={passwords.currentPassword} onChange={(event) => setPasswords({ ...passwords, currentPassword: event.target.value })} />
            <Field label="New password" type="password" value={passwords.newPassword} onChange={(event) => setPasswords({ ...passwords, newPassword: event.target.value })} />
            <Button variant="outline" type="submit">Change password</Button>
            <Notice>{accountMessage}</Notice>
          </form>
        </aside>
      </div>
    </AppFrame>
  );
}

function MetricCard({ icon, label, value, sub }) {
  return (
    <div className="metric-card">
      <div className="metric-icon"><Icon name={icon} /></div>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
        {sub && <p>{sub}</p>}
      </div>
    </div>
  );
}

function ToggleRow({ label, checked, onChange }) {
  return (
    <label className="toggle-row">
      <span>{label}</span>
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      <i />
    </label>
  );
}

function AdminPage() {
  const { state, ollama, reload, refreshModels } = useAppState();
  const [form, setForm] = useState({ name: "", email: "", role: "user", departmentId: "dept-general", password: "" });
  const [settings, setSettings] = useState(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (state?.settings) setSettings({ ...state.settings });
  }, [state?.settings]);

  const localModels = useMemo(() => {
    if (!state) return [];
    return state.models.filter((model) => model.provider === "ollama");
  }, [state]);

  if (!state || !settings) return <LoadingPage />;
  if (state.activeUser.role !== "admin") {
    window.location.href = "/app";
    return null;
  }

  async function createUser(event) {
    event.preventDefault();
    setNotice("");
    try {
      await api("/api/admin/users", {
        method: "POST",
        body: JSON.stringify({
          ...form,
          rights: form.role === "admin" ? ["admin:manage", "chat:use", "models:manage", "users:manage"] : ["chat:use"],
        }),
      });
      setForm({ name: "", email: "", role: "user", departmentId: "dept-general", password: "" });
      setNotice("User created.");
      await reload();
    } catch (err) {
      setNotice(err.message);
    }
  }

  async function resetPassword(userId) {
    await api(`/api/admin/users/${userId}/reset-password`, { method: "POST", body: JSON.stringify({ password: "CHANGE_ME_ON_FIRST_BOOT" }) });
    setNotice("Password reset to CHANGE_ME_ON_FIRST_BOOT.");
  }

  async function toggleUser(user) {
    await api(`/api/admin/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ active: !user.active }) });
    await reload();
  }

  async function saveSettings() {
    await api("/api/admin/settings", {
      method: "POST",
      body: JSON.stringify(settings),
    });
    setNotice("Settings saved.");
    await reload();
  }

  return (
    <AppFrame
      title="Company Dashboard"
      subtitle="Admin control"
      active="admin"
      state={state}
      ollama={ollama}
    >
      <section className="metrics-grid">
        <MetricCard icon="memory" label="Local Models" value={state.models.filter((m) => m.provider === "ollama" && m.status === "available").length} sub="Discovered from Ollama" />
        <MetricCard icon="group" label="Users" value={state.users.length} sub="Admin and employees" />
        <MetricCard icon="hub" label="Groups" value={state.groups.length} sub="Shared access rules" />
        <MetricCard icon="domain" label="Departments" value={state.departments.length} sub="Company structure" />
      </section>

      <section className="admin-grid">
        <div className="card span-8">
          <div className="card-heading">
            <div>
              <h2>Available Local Models</h2>
              <p>Live discovery from your local Ollama service.</p>
            </div>
            <Button onClick={refreshModels} variant="soft"><Icon name="refresh" /> Refresh</Button>
          </div>
          {localModels.length ? (
            <div className="model-table">
              <div className="table-head"><span>Model</span><span>Status</span><span>Capabilities</span></div>
              {localModels.map((model) => (
                <div className="table-row" key={model.id}>
                  <div>
                    <strong>{model.name}</strong>
                    <small>{model.model}</small>
                  </div>
                  <Pill tone={model.status === "available" ? "success" : "default"}>{model.status}</Pill>
                  <span className="muted">{(model.capabilities || []).join(", ") || "General"}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="empty-state compact">
              <Icon name="cloud_off" />
              <div>
                <h3>No local models found</h3>
                <p>Start Ollama on your laptop and click Refresh.</p>
              </div>
            </div>
          )}
        </div>

        <div className="card span-4">
          <div className="card-heading">
            <div>
              <h2>Access Model</h2>
              <p>How eligibility is decided.</p>
            </div>
          </div>
          <div className="access-list">
            {[
              ["User grants", "Direct allow or restrict list."],
              ["Group grants", "Shared access for project teams."],
              ["Department grants", "Default access by company function."],
              ["Storage", "PostgreSQL · MongoDB · Redis"],
            ].map(([title, copy]) => (
              <div className="access-card" key={title}>
                <strong>{title}</strong>
                <span>{copy}</span>
              </div>
            ))}
          </div>
        </div>

        <form className="card span-5" onSubmit={createUser}>
          <div className="card-heading">
            <div>
              <h2>Create User</h2>
              <p>Add employees with company-controlled rights.</p>
            </div>
          </div>
          <div className="form-grid one">
            <Field label="Name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            <Field label="Email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
            <div className="form-grid two">
              <SelectField label="Role" value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value })}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </SelectField>
              <SelectField label="Department" value={form.departmentId} onChange={(event) => setForm({ ...form, departmentId: event.target.value })}>
                {state.departments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}
              </SelectField>
            </div>
            <Field label="Temporary password" type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} />
            <Button type="submit" className="w-full">Create User</Button>
            <Notice>{notice}</Notice>
          </div>
        </form>

        <div className="card span-7">
          <div className="card-heading">
            <div>
              <h2>Users</h2>
              <p>Manage employee access and account status.</p>
            </div>
          </div>
          <div className="user-list">
            {state.users.map((user) => (
              <div className="user-row" key={user.id}>
                <div className="avatar">{user.name.slice(0, 2).toUpperCase()}</div>
                <div>
                  <strong>{user.name}</strong>
                  <span>{user.email} · {user.role} · {(user.rights || []).join(", ")}</span>
                </div>
                <Pill tone={user.active ? "success" : "default"}>{user.active ? "active" : "inactive"}</Pill>
                <Button variant="ghost" onClick={() => resetPassword(user.id)}>Reset</Button>
                <Button variant="ghost" onClick={() => toggleUser(user)}>{user.active ? "Deactivate" : "Activate"}</Button>
              </div>
            ))}
          </div>
        </div>

        <div className="card span-4">
          <div className="card-heading">
            <div>
              <h2>System Settings</h2>
              <p>Default company AI behavior.</p>
            </div>
          </div>
          <div className="settings-list">
            <ToggleRow label="Auto Router enabled" checked={settings.routerEnabled} onChange={(value) => setSettings({ ...settings, routerEnabled: value })} />
            <ToggleRow label="Allow API models" checked={settings.allowApiModels} onChange={(value) => setSettings({ ...settings, allowApiModels: value })} />
            <ToggleRow label="Local-first by default" checked={settings.localOnlyDefault} onChange={(value) => setSettings({ ...settings, localOnlyDefault: value })} />
            <Button onClick={saveSettings} type="button" className="w-full">Save Settings</Button>
          </div>
        </div>

        <div className="card span-8">
          <div className="card-heading">
            <div>
              <h2>Audit Trail</h2>
              <p>Recent admin and account activity.</p>
            </div>
          </div>
          <div className="timeline">
            {state.audit.length ? state.audit.map((item) => (
              <div className="timeline-row" key={item.id}>
                <Icon name="history" />
                <div>
                  <strong>{item.detail}</strong>
                  <span>{item.actor} · {new Date(item.createdAt).toLocaleString()}</span>
                </div>
              </div>
            )) : (
              <div className="empty-state inline">
                <Icon name="history" />
                <div>
                  <h3>No audit events yet</h3>
                  <p>Activity will appear here as admins and users work.</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>
    </AppFrame>
  );
}

function Root() {
  const path = window.location.pathname;
  if (path === "/admin") return <AdminPage />;
  if (path === "/app") return <WorkspacePage />;
  return <LoginPage />;
}

createRoot(document.getElementById("root")).render(<Root />);
