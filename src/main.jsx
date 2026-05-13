import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

const BRAND = {
  primary: "#0E9384",
  dark: "#101828",
  accent: "#2DD4BF",
  background: "#F8FAFC",
  success: "#17B26A",
};

const DEFAULT_ADMIN_PASSWORD = "CHANGE_ME_ON_FIRST_BOOT";

function Icon({ name, className = "" }) {
  return <span className={`material-symbols-rounded ${className}`}>{name}</span>;
}

function cn(...classes) {
  return classes.filter(Boolean).join(" ");
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

function Badge({ children, color = "primary", variant = "light", className = "" }) {
  const variants = {
    light: {
      primary: "bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300",
      success: "bg-success-50 text-success-700 dark:bg-success-500/15 dark:text-success-400",
      warning: "bg-warning-50 text-warning-700 dark:bg-warning-500/15 dark:text-warning-400",
      error: "bg-error-50 text-error-700 dark:bg-error-500/15 dark:text-error-400",
      gray: "bg-gray-100 text-gray-700 dark:bg-white/5 dark:text-white/80",
    },
    solid: {
      primary: "bg-brand-500 text-white",
      success: "bg-success-500 text-white",
      warning: "bg-warning-500 text-white",
      error: "bg-error-500 text-white",
      gray: "bg-gray-700 text-white",
    },
  };
  return (
    <span className={cn("inline-flex items-center justify-center gap-1 rounded-full px-2.5 py-0.5 text-theme-xs font-medium", variants[variant][color], className)}>
      {children}
    </span>
  );
}

function Button({ children, variant = "primary", className = "", ...props }) {
  const variants = {
    primary: "bg-brand-500 text-white shadow-theme-xs hover:bg-brand-600 disabled:bg-brand-300",
    outline: "border border-gray-300 bg-white text-gray-700 shadow-theme-xs hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300 dark:hover:bg-white/[0.03]",
    ghost: "text-brand-600 hover:bg-brand-50 dark:text-brand-300 dark:hover:bg-brand-500/10",
    soft: "bg-brand-50 text-brand-600 hover:bg-brand-100 dark:bg-brand-500/15 dark:text-brand-300",
  };
  return (
    <button className={cn("inline-flex h-11 items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium transition", variants[variant], className)} {...props}>
      {children}
    </button>
  );
}

function Label({ children }) {
  return <span className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">{children}</span>;
}

function Input({ label, className = "", ...props }) {
  return (
    <label className={className}>
      <Label>{label}</Label>
      <input
        className="dark:bg-dark-900 h-11 w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 shadow-theme-xs placeholder:text-gray-400 focus:border-brand-300 focus:outline-hidden focus:ring-3 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30 dark:focus:border-brand-800"
        {...props}
      />
    </label>
  );
}

function Select({ label, children, className = "", ...props }) {
  return (
    <label className={className}>
      <Label>{label}</Label>
      <div className="relative">
        <select
          className="dark:bg-dark-900 h-11 w-full appearance-none rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 pr-10 text-sm text-gray-800 shadow-theme-xs focus:border-brand-300 focus:outline-hidden focus:ring-3 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:focus:border-brand-800"
          {...props}
        >
          {children}
        </select>
        <Icon name="keyboard_arrow_down" className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-gray-500" />
      </div>
    </label>
  );
}

function Textarea({ ...props }) {
  return (
    <textarea
      className="dark:bg-dark-900 min-h-[118px] w-full resize-y rounded-xl border border-gray-300 bg-transparent px-4 py-3 text-sm text-gray-800 shadow-theme-xs placeholder:text-gray-400 focus:border-brand-300 focus:outline-hidden focus:ring-3 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
      {...props}
    />
  );
}

function Alert({ children, type = "info" }) {
  if (!children) return null;
  const styles = {
    info: "border-brand-200 bg-brand-50 text-brand-700 dark:border-brand-500/30 dark:bg-brand-500/10 dark:text-brand-300",
    error: "border-error-200 bg-error-50 text-error-700 dark:border-error-500/30 dark:bg-error-500/10 dark:text-error-300",
  };
  return <div className={cn("rounded-xl border px-4 py-3 text-sm font-medium", styles[type])}>{children}</div>;
}

function ComponentCard({ title, desc, children, className = "", actions, ...props }) {
  return (
    <div className={cn("rounded-2xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-white/[0.03]", className)} {...props}>
      <div className="flex items-start justify-between gap-4 px-5 py-4 sm:px-6">
        <div>
          <h3 className="text-base font-medium text-gray-800 dark:text-white/90">{title}</h3>
          {desc && <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{desc}</p>}
        </div>
        {actions}
      </div>
      <div className="border-t border-gray-100 p-5 dark:border-gray-800 sm:p-6">{children}</div>
    </div>
  );
}

function BrandLogo({ compact = false, href }) {
  const content = (
    <>
      <div className="flex size-10 items-center justify-center rounded-xl bg-brand-500 text-sm font-black text-white shadow-theme-xs">ON</div>
      {!compact && (
        <div>
          <p className="text-base font-semibold text-gray-900 dark:text-white">Olla Nest</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">AI Workspace</p>
        </div>
      )}
    </>
  );
  if (href) {
    return (
      <a href={href} className={cn("flex items-center rounded-xl outline-none focus:ring-3 focus:ring-brand-500/10", compact ? "justify-center" : "gap-3")}>
        {content}
      </a>
    );
  }
  return (
    <div className={cn("flex items-center", compact ? "justify-center" : "gap-3")}>
      {content}
    </div>
  );
}

function LoadingPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 dark:bg-gray-950">
      <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-theme-lg dark:border-gray-800 dark:bg-gray-900">
        <BrandLogo />
      </div>
    </div>
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
    <div className="relative min-h-screen overflow-hidden bg-white dark:bg-gray-950">
      <div className="grid min-h-screen lg:grid-cols-[1fr_480px]">
        <section className="relative hidden items-center overflow-hidden bg-gray-950 px-16 py-12 lg:flex">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(45,212,191,0.22),transparent_32%),radial-gradient(circle_at_80%_70%,rgba(14,147,132,0.25),transparent_30%)]" />
          <div className="relative max-w-xl">
            <BrandLogo />
            <h1 className="mt-24 text-title-lg font-semibold tracking-tight text-white">
              Private Cloud AI for company teams.
            </h1>
            <p className="mt-5 max-w-lg text-theme-md leading-8 text-gray-300">
              Govern local Ollama models, route every request intelligently, and keep employees inside approved access boundaries.
            </p>
            <div className="mt-10 grid gap-4">
              {["Local-first model routing", "User, group, and department controls", "PostgreSQL + MongoDB + Redis architecture"].map((item) => (
                <div key={item} className="flex items-center gap-3 text-sm font-medium text-gray-200">
                  <span className="flex size-8 items-center justify-center rounded-lg bg-brand-500/20 text-brand-300"><Icon name="check" /></span>
                  {item}
                </div>
              ))}
            </div>
          </div>
        </section>
        <section className="flex items-center justify-center bg-gray-50 px-6 py-12 dark:bg-gray-950">
          <form onSubmit={submit} className="w-full max-w-md">
            <div className="mb-8 lg:hidden">
              <BrandLogo />
            </div>
            <div className="rounded-2xl border border-gray-200 bg-white p-7 shadow-theme-lg dark:border-gray-800 dark:bg-gray-900 sm:p-8">
              <p className="mb-2 text-theme-xs font-medium uppercase tracking-[0.18em] text-brand-600">Secure sign in</p>
              <h2 className="text-title-sm font-semibold text-gray-900 dark:text-white/90">Welcome back</h2>
              <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">Use your company account to access Olla Nest.</p>
              <div className="mt-7 space-y-5">
                <Input label="Email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" />
                <Input label="Password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
                <Button type="submit" className="w-full">Sign in</Button>
                <div className="rounded-xl border border-gray-200 bg-gray-50 p-4 text-sm text-gray-600 dark:border-gray-800 dark:bg-white/[0.03] dark:text-gray-400">
                  <p className="font-medium text-gray-800 dark:text-white/90">{hint || "Default admin is created on first boot"}</p>
                  <p className="mt-1">Password: {DEFAULT_ADMIN_PASSWORD}</p>
                </div>
                <Alert type="error">{error}</Alert>
              </div>
            </div>
          </form>
        </section>
      </div>
    </div>
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

function Sidebar({ active, state }) {
  const isAdmin = state?.activeUser?.role === "admin";
  const homeHref = isAdmin ? "/admin" : "/app";
  const nav = isAdmin
    ? [
        { href: "/admin", label: "Admin", icon: "dashboard", key: "admin" },
        { href: "/app", label: "Workspace", icon: "forum", key: "workspace" },
      ]
    : [{ href: "/app", label: "Workspace", icon: "forum", key: "workspace" }];
  const secondary = [
    { label: "Model Router", icon: "route", href: isAdmin ? "/admin#models" : "/app#router" },
    { label: "Access Control", icon: "verified_user", href: isAdmin ? "/admin#access" : "/app#access" },
    { label: "Data Stores", icon: "database", href: "/admin#storage" },
  ];

  return (
    <aside className="fixed left-0 top-0 z-50 hidden h-screen w-[290px] flex-col border-r border-gray-200 bg-white px-5 dark:border-gray-800 dark:bg-gray-900 lg:flex">
      <div className="py-8">
        <BrandLogo href={homeHref} />
      </div>
      <nav className="flex flex-1 flex-col overflow-y-auto no-scrollbar">
        <div>
          <h2 className="mb-4 text-xs uppercase leading-5 text-gray-400">Menu</h2>
          <ul className="flex flex-col gap-4">
            {nav.map((item) => (
              <li key={item.key}>
                <a href={item.href} className={cn("menu-item group", active === item.key ? "menu-item-active" : "menu-item-inactive")}>
                  <Icon name={item.icon} className={cn("menu-item-icon-size", active === item.key ? "menu-item-icon-active" : "menu-item-icon-inactive")} />
                  <span className="menu-item-text">{item.label}</span>
                </a>
              </li>
            ))}
          </ul>
        </div>
        <div className="mt-8">
          <h2 className="mb-4 text-xs uppercase leading-5 text-gray-400">Platform</h2>
          <ul className="flex flex-col gap-4">
            {secondary.map((item) => (
              <li key={item.label}>
                <a href={item.href} className="menu-item menu-item-inactive group">
                  <Icon name={item.icon} className="menu-item-icon-size menu-item-icon-inactive" />
                  <span className="menu-item-text">{item.label}</span>
                </a>
              </li>
            ))}
          </ul>
        </div>
        <div className="mt-auto rounded-2xl border border-brand-100 bg-brand-25 p-4 dark:border-brand-500/20 dark:bg-brand-500/10">
          <div className="flex items-center gap-3">
            <span className="flex size-10 items-center justify-center rounded-xl bg-brand-500 text-white"><Icon name="lock" /></span>
            <div>
              <p className="text-sm font-medium text-gray-800 dark:text-white/90">Local-first</p>
              <p className="text-xs text-gray-500 dark:text-gray-400">Company controlled AI</p>
            </div>
          </div>
        </div>
      </nav>
    </aside>
  );
}

function Header({ title, subtitle, state, ollama, rightSlot }) {
  async function logout() {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
  }

  return (
    <header className="sticky top-0 z-40 flex w-full border-b border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
      <div className="flex grow flex-col justify-between gap-3 px-4 py-4 sm:px-6 lg:flex-row lg:items-center">
        <div className="flex items-center gap-4">
          <div className="lg:hidden"><BrandLogo /></div>
          <div>
            <p className="text-theme-xs font-medium uppercase tracking-[0.16em] text-brand-600">{subtitle}</p>
            <h1 className="mt-1 text-title-sm font-semibold text-gray-900 dark:text-white/90">{title}</h1>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <div className="hidden h-11 min-w-[300px] items-center gap-3 rounded-lg border border-gray-200 bg-transparent px-4 text-sm text-gray-500 shadow-theme-xs dark:border-gray-800 dark:text-gray-400 xl:flex">
            <Icon name="search" />
            <span>Search Olla Nest</span>
            <kbd className="ml-auto rounded-md border border-gray-200 bg-gray-50 px-1.5 py-0.5 text-xs dark:border-gray-800 dark:bg-white/[0.03]">⌘K</kbd>
          </div>
          <Badge color={ollama?.ok === false ? "warning" : "success"}>
            {ollama?.ok === false ? "Ollama offline" : `${ollama?.models?.length ?? 0} local models`}
          </Badge>
          {rightSlot}
          <div className="flex items-center gap-3 rounded-full border border-gray-200 bg-white py-1.5 pl-2 pr-3 shadow-theme-xs dark:border-gray-800 dark:bg-white/[0.03]">
            <div className="flex size-8 items-center justify-center rounded-full bg-brand-50 text-xs font-semibold text-brand-600">{state?.activeUser?.name?.slice(0, 2).toUpperCase() || "ON"}</div>
            <div className="hidden sm:block">
              <p className="text-sm font-medium text-gray-700 dark:text-gray-300">{state?.activeUser?.name}</p>
              <p className="text-xs text-gray-500 dark:text-gray-400">{state?.activeUser?.role}</p>
            </div>
          </div>
          <Button variant="outline" onClick={logout}>Logout</Button>
        </div>
      </div>
    </header>
  );
}

function AppLayout({ active, title, subtitle, state, ollama, children, rightSlot }) {
  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950 xl:flex">
      <Sidebar active={active} state={state} />
      <div className="flex-1 lg:ml-[290px]">
        <Header title={title} subtitle={subtitle} state={state} ollama={ollama} rightSlot={rightSlot} />
        <main className="mx-auto max-w-[1536px] p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}

function MetricCard({ icon, label, value, sub }) {
  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-5 dark:border-gray-800 dark:bg-white/[0.03] md:p-6">
      <div className="flex size-12 items-center justify-center rounded-xl bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-white/90">
        <Icon name={icon} />
      </div>
      <div className="mt-5 flex items-end justify-between">
        <div>
          <span className="text-sm text-gray-500 dark:text-gray-400">{label}</span>
          <h4 className="mt-2 text-title-sm font-bold text-gray-800 dark:text-white/90">{value}</h4>
          {sub && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{sub}</p>}
        </div>
        <Badge color="primary">Live</Badge>
      </div>
    </div>
  );
}

function WorkspacePage() {
  const { state, ollama, reload, refreshModels } = useAppState();
  const [mode, setMode] = useState("ask");
  const [message, setMessage] = useState("");
  const [manualModelId, setManualModelId] = useState("");
  const [router, setRouter] = useState("Send a message and Olla Nest will choose the best approved model.");
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

  async function clearChat() {
    await api("/api/chat/clear", { method: "POST", body: "{}" });
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

  return (
    <AppLayout
      active="workspace"
      title="Workspace"
      subtitle={`${state.activeUser.name} · ${department?.name || "General"}`}
      state={state}
      ollama={ollama}
      rightSlot={<Button variant="soft" onClick={clearChat}><Icon name="add" /> New chat</Button>}
    >
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
        <ComponentCard title="AI Workspace" desc="Use auto routing or select one approved model manually." className="min-h-[calc(100vh-154px)]">
          <div className="flex flex-col gap-5">
            <div className="flex flex-wrap gap-2">
              {["ask", "build", "review", "fix", "learn"].map((item) => (
                <button key={item} type="button" onClick={() => setMode(item)} className={cn("rounded-lg px-3 py-2 text-sm font-medium capitalize", mode === item ? "bg-brand-500 text-white" : "bg-gray-100 text-gray-700 hover:bg-gray-200 dark:bg-white/[0.03] dark:text-gray-300")}>
                  {item}
                </button>
              ))}
            </div>
            <div className="min-h-[360px] space-y-4 rounded-2xl border border-gray-100 bg-gray-50 p-4 dark:border-gray-800 dark:bg-gray-950/40">
              {messages.length ? messages.map((item, index) => (
                <div key={`${item.role}-${index}`} className={cn("flex", item.role === "user" && "justify-end")}>
                  <div className={cn("max-w-[780px] rounded-2xl border px-4 py-3", item.role === "user" ? "border-brand-100 bg-brand-50 text-gray-800 dark:border-brand-500/20 dark:bg-brand-500/10 dark:text-white/90" : "border-gray-200 bg-white text-gray-800 dark:border-gray-800 dark:bg-white/[0.03] dark:text-white/90")}>
                    <p className="mb-1 text-xs font-medium text-gray-500 dark:text-gray-400">{item.role === "assistant" ? item.modelName || "Assistant" : `${state.activeUser.name} · ${item.mode || mode}`}</p>
                    <p className="whitespace-pre-wrap text-sm leading-6">{item.content}</p>
                  </div>
                </div>
              )) : (
                <div className="flex min-h-[320px] items-center justify-center">
                  <div className="max-w-md text-center">
                    <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-300">
                      <Icon name="auto_awesome" />
                    </div>
                    <h2 className="text-xl font-semibold text-gray-900 dark:text-white/90">Ask anything your approved models can handle.</h2>
                    <p className="mt-2 text-sm leading-6 text-gray-500 dark:text-gray-400">The router chooses from models allowed for your user, group, and department.</p>
                  </div>
                </div>
              )}
            </div>
            <form className="space-y-3" onSubmit={send}>
              <Textarea value={message} onChange={(event) => setMessage(event.target.value)} placeholder="Message Olla Nest..." />
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <select value={manualModelId} onChange={(event) => setManualModelId(event.target.value)} className="h-11 rounded-lg border border-gray-300 bg-white px-4 text-sm text-gray-700 shadow-theme-xs focus:border-brand-300 focus:outline-hidden focus:ring-3 focus:ring-brand-500/10 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90">
                  <option value="">Auto Router</option>
                  {models.map((model) => <option key={model.id} value={model.id}>{model.name}</option>)}
                </select>
                <Button type="submit"><Icon name="send" /> Send</Button>
              </div>
            </form>
          </div>
        </ComponentCard>

        <div className="space-y-6">
          <ComponentCard
            title="Access"
            desc={`${department?.name || "General"} department`}
            className="scroll-mt-24"
            id="access"
            actions={<Button variant="soft" onClick={refreshModels}><Icon name="refresh" /> Refresh</Button>}
          >
            <div className="flex flex-wrap gap-2">
              {models.length ? models.map((model) => <Badge key={model.id} color="gray">{model.name}</Badge>) : <Badge color="warning">No approved local models</Badge>}
            </div>
          </ComponentCard>
          <ComponentCard title="Router" desc="Latest routing decision" className="scroll-mt-24" id="router">
            <p className="text-sm leading-6 text-gray-600 dark:text-gray-400">{router}</p>
          </ComponentCard>
          <ComponentCard title="Account" desc={state.activeUser.email} className="scroll-mt-24">
            <form className="space-y-4" onSubmit={changePassword}>
              <Input label="Current password" type="password" value={passwords.currentPassword} onChange={(event) => setPasswords({ ...passwords, currentPassword: event.target.value })} />
              <Input label="New password" type="password" value={passwords.newPassword} onChange={(event) => setPasswords({ ...passwords, newPassword: event.target.value })} />
              <Button variant="outline" type="submit" className="h-12 w-full">Change password</Button>
              <Alert>{accountMessage}</Alert>
            </form>
          </ComponentCard>
        </div>
      </div>
    </AppLayout>
  );
}

function ToggleRow({ label, checked, onChange }) {
  return (
    <label className="flex items-center justify-between gap-4 rounded-xl border border-gray-200 px-4 py-3 dark:border-gray-800">
      <span className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</span>
      <input className="tailadmin-switch" type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
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
    <AppLayout active="admin" title="Company Dashboard" subtitle="Admin control" state={state} ollama={ollama}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4 md:gap-6">
        <MetricCard icon="memory" label="Local Models" value={state.models.filter((m) => m.provider === "ollama" && m.status === "available").length} sub="Discovered from Ollama" />
        <MetricCard icon="group" label="Users" value={state.users.length} sub="Admin and employees" />
        <MetricCard icon="hub" label="Groups" value={state.groups.length} sub="Shared access rules" />
        <MetricCard icon="domain" label="Departments" value={state.departments.length} sub="Company structure" />
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 xl:grid-cols-12">
        <ComponentCard
          title="Available Local Models"
          desc="Live discovery from your local Ollama service."
          className="scroll-mt-24 xl:col-span-8"
          id="models"
          actions={<Button variant="soft" onClick={refreshModels}><Icon name="refresh" /> Refresh</Button>}
        >
          {localModels.length ? (
            <div className="overflow-hidden rounded-xl border border-gray-200 dark:border-gray-800">
              <div className="grid grid-cols-[minmax(180px,1fr)_120px_minmax(220px,1.2fr)] bg-gray-50 px-5 py-3 text-xs font-medium uppercase text-gray-500 dark:bg-white/[0.03]">
                <span>Model</span><span>Status</span><span>Capabilities</span>
              </div>
              {localModels.map((model) => (
                <div className="grid grid-cols-[minmax(180px,1fr)_120px_minmax(220px,1.2fr)] items-center border-t border-gray-100 px-5 py-4 text-sm dark:border-gray-800" key={model.id}>
                  <div>
                    <p className="font-medium text-gray-800 dark:text-white/90">{model.name}</p>
                    <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{model.model}</p>
                  </div>
                  <Badge color={model.status === "available" ? "success" : "gray"}>{model.status}</Badge>
                  <p className="text-gray-500 dark:text-gray-400">{(model.capabilities || []).join(", ") || "General"}</p>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-2xl border border-dashed border-gray-300 bg-gray-50 p-8 dark:border-gray-800 dark:bg-white/[0.03]">
              <div className="flex items-start gap-4">
                <span className="flex size-12 items-center justify-center rounded-xl bg-warning-50 text-warning-600"><Icon name="cloud_off" /></span>
                <div>
                  <h3 className="font-medium text-gray-800 dark:text-white/90">No local models found</h3>
                  <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">Start Ollama on your laptop and click Refresh.</p>
                </div>
              </div>
            </div>
          )}
        </ComponentCard>

        <ComponentCard title="Access Model" desc="How eligibility is decided." className="scroll-mt-24 xl:col-span-4" id="access">
          <div className="space-y-3">
            {[
              ["User grants", "Direct allow or restrict list."],
              ["Group grants", "Shared access for project teams."],
              ["Department grants", "Default access by company function."],
              ["Storage", "PostgreSQL · MongoDB · Redis"],
            ].map(([title, copy]) => (
              <div className="rounded-xl border border-gray-200 p-4 dark:border-gray-800" key={title} id={title === "Storage" ? "storage" : undefined}>
                <p className="font-medium text-gray-800 dark:text-white/90">{title}</p>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{copy}</p>
              </div>
            ))}
          </div>
        </ComponentCard>

        <ComponentCard title="Create User" desc="Add employees with company-controlled rights." className="xl:col-span-5">
          <form className="space-y-4" onSubmit={createUser}>
            <Input label="Name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            <Input label="Email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Select label="Role" value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value })}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </Select>
              <Select label="Department" value={form.departmentId} onChange={(event) => setForm({ ...form, departmentId: event.target.value })}>
                {state.departments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}
              </Select>
            </div>
            <Input label="Temporary password" type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} />
            <Button type="submit" className="w-full">Create User</Button>
            <Alert>{notice}</Alert>
          </form>
        </ComponentCard>

        <ComponentCard title="Users" desc="Manage employee access and account status." className="xl:col-span-7">
          <div className="space-y-3">
            {state.users.map((user) => (
              <div className="grid grid-cols-[42px_minmax(0,1fr)_auto_auto_auto] items-center gap-3 rounded-xl border border-gray-200 p-3 dark:border-gray-800" key={user.id}>
                <div className="flex size-10 items-center justify-center rounded-full bg-brand-50 text-xs font-semibold text-brand-600">{user.name.slice(0, 2).toUpperCase()}</div>
                <div>
                  <p className="font-medium text-gray-800 dark:text-white/90">{user.name}</p>
                  <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{user.email} · {user.role} · {(user.rights || []).join(", ")}</p>
                </div>
                <Badge color={user.active ? "success" : "gray"}>{user.active ? "active" : "inactive"}</Badge>
                <Button variant="ghost" onClick={() => resetPassword(user.id)}>Reset</Button>
                <Button variant="ghost" onClick={() => toggleUser(user)}>{user.active ? "Deactivate" : "Activate"}</Button>
              </div>
            ))}
          </div>
        </ComponentCard>

        <ComponentCard title="System Settings" desc="Default company AI behavior." className="xl:col-span-4">
          <div className="space-y-3">
            <ToggleRow label="Auto Router enabled" checked={settings.routerEnabled} onChange={(value) => setSettings({ ...settings, routerEnabled: value })} />
            <ToggleRow label="Allow API models" checked={settings.allowApiModels} onChange={(value) => setSettings({ ...settings, allowApiModels: value })} />
            <ToggleRow label="Local-first by default" checked={settings.localOnlyDefault} onChange={(value) => setSettings({ ...settings, localOnlyDefault: value })} />
            <Button onClick={saveSettings} type="button" className="w-full">Save Settings</Button>
          </div>
        </ComponentCard>

        <ComponentCard title="Audit Trail" desc="Recent admin and account activity." className="xl:col-span-8">
          <div className="space-y-1">
            {state.audit.length ? state.audit.map((item) => (
              <div className="flex gap-3 border-b border-gray-100 py-4 last:border-0 dark:border-gray-800" key={item.id}>
                <span className="flex size-9 items-center justify-center rounded-lg bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300"><Icon name="history" /></span>
                <div>
                  <p className="font-medium text-gray-800 dark:text-white/90">{item.detail}</p>
                  <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{item.actor} · {new Date(item.createdAt).toLocaleString()}</p>
                </div>
              </div>
            )) : (
              <div className="rounded-xl border border-dashed border-gray-300 p-6 text-sm text-gray-500 dark:border-gray-800 dark:text-gray-400">No audit events yet.</div>
            )}
          </div>
        </ComponentCard>
      </div>
    </AppLayout>
  );
}

function Root() {
  const path = window.location.pathname;
  if (path === "/admin") return <AdminPage />;
  if (path === "/app") return <WorkspacePage />;
  return <LoginPage />;
}

createRoot(document.getElementById("root")).render(<Root />);
