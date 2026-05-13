import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Alert,
  AppBar,
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CssBaseline,
  Divider,
  Drawer,
  FormControlLabel,
  IconButton,
  InputAdornment,
  LinearProgress,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  MenuItem,
  Paper,
  Select,
  Stack,
  Switch,
  Tab,
  Tabs,
  TextField,
  ThemeProvider,
  Toolbar,
  Tooltip,
  Typography,
  createTheme,
} from "@mui/material";
import "./styles.css";

const googleBlue = "#1a73e8";
const brandTeal = "#117a8b";

const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: googleBlue },
    secondary: { main: brandTeal },
    background: { default: "#F8F9FA", paper: "#FFFFFF" },
    text: { primary: "#202124", secondary: "#5F6368" },
  },
  shape: { borderRadius: 16 },
  typography: {
    fontFamily: "Inter, Roboto, Arial, sans-serif",
    h4: { fontWeight: 750, letterSpacing: "-0.02em" },
    h5: { fontWeight: 750, letterSpacing: "-0.01em" },
    h6: { fontWeight: 700 },
    button: { textTransform: "none", fontWeight: 700 },
  },
  components: {
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 16,
          boxShadow: "0 1px 3px rgba(60,64,67,.18)",
          border: "1px solid #E8EAED",
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: { borderRadius: 999, boxShadow: "none" },
      },
    },
    MuiTextField: {
      defaultProps: { variant: "outlined", size: "small" },
    },
    MuiCardContent: {
      styleOverrides: {
        root: { padding: 18, "&:last-child": { paddingBottom: 18 } },
      },
    },
  },
});

function Icon({ children, filled = false }) {
  return <span className={`material-symbols-rounded ${filled ? "icon-filled" : ""}`}>{children}</span>;
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

function Shell({ children, title, subtitle, nav, actions }) {
  return (
    <Box className="min-h-screen bg-[#F8F9FA]">
      <Drawer variant="permanent" PaperProps={{ className: "rail" }}>
        <Stack spacing={3} className="h-full px-3 py-5">
          <Stack direction="row" spacing={1.5} alignItems="center" className="px-2">
            <Avatar sx={{ bgcolor: "#202124", width: 38, height: 38, fontSize: 13, fontWeight: 800 }}>ON</Avatar>
            <Box>
              <Typography fontWeight={800} lineHeight={1}>Olla Nest</Typography>
              <Typography variant="caption" color="text.secondary">AI Workspace</Typography>
            </Box>
          </Stack>
          <List className="space-y-1">
            {nav.map((item) => (
              <ListItemButton key={item.href} component="a" href={item.href} selected={item.active} className="nav-pill">
                <ListItemIcon sx={{ minWidth: 36 }}><Icon filled={item.active}>{item.icon}</Icon></ListItemIcon>
                <ListItemText primary={item.label} primaryTypographyProps={{ fontWeight: 700, fontSize: 14 }} />
              </ListItemButton>
            ))}
          </List>
          <Box flex={1} />
          <Typography variant="caption" color="text.secondary" className="px-3">Local-first. Company-controlled.</Typography>
        </Stack>
      </Drawer>
      <Box className="content-with-rail">
        <AppBar color="transparent" elevation={0} position="sticky" className="top-appbar">
          <Toolbar className="gap-4 dense-toolbar">
            <Box flex={1}>
              <Typography variant="caption" color="secondary" fontWeight={800} letterSpacing=".12em">{subtitle}</Typography>
              <Typography variant="h4" className="page-title">{title}</Typography>
            </Box>
            <Paper className="search-pill compact-search" variant="outlined">
              <Icon>search</Icon>
              <Typography color="text.secondary" fontSize={14}>Search Olla Nest</Typography>
            </Paper>
            {actions}
          </Toolbar>
        </AppBar>
        <Box className="page-wrap">{children}</Box>
      </Box>
    </Box>
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
    <Box className="auth-canvas">
      <Card className="auth-card-m3">
        <CardContent>
          <Stack direction="row" spacing={1.5} alignItems="center" mb={2.25}>
            <Avatar sx={{ bgcolor: "#202124", fontWeight: 800 }}>ON</Avatar>
            <Box>
              <Typography fontWeight={800}>Olla Nest</Typography>
              <Typography variant="caption" color="text.secondary">Company AI Workspace</Typography>
            </Box>
          </Stack>
          <Typography variant="h4" mb={0.5} className="auth-title">Sign in</Typography>
          <Typography color="text.secondary" mb={2}>Use your company account to access the workspace.</Typography>
          <Stack component="form" spacing={2} onSubmit={submit}>
            <TextField label="Email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" className="clean-input" />
            <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" className="clean-input" />
            <Button type="submit" variant="contained" size="large">Sign in</Button>
          </Stack>
          {hint && <Typography mt={2} variant="body2" color="text.secondary">{hint}</Typography>}
          {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
        </CardContent>
      </Card>
    </Box>
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

function WorkspacePage() {
  const { state, ollama, reload } = useAppState();
  const [mode, setMode] = useState("ask");
  const [message, setMessage] = useState("");
  const [manualModelId, setManualModelId] = useState("");
  const [router, setRouter] = useState("Send a request to see routing logic.");
  const [accountMessage, setAccountMessage] = useState("");
  const [passwords, setPasswords] = useState({ currentPassword: "", newPassword: "" });

  if (!state) return <LinearProgress />;
  const models = allowedModels(state);
  const chat = state.chats.find((item) => item.userId === state.activeUser.id) || state.chats[0];
  const department = state.departments.find((item) => item.id === state.activeUser.departmentId);

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

  async function logout() {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
  }

  return (
    <Shell
      title="Workspace"
      subtitle={`${state.activeUser.name} · ${department?.name || "General"}`}
      nav={[
        { href: "/app", label: "Workspace", icon: "chat", active: true },
        ...(state.activeUser.role === "admin" ? [{ href: "/admin", label: "Admin", icon: "admin_panel_settings" }] : []),
      ]}
      actions={<><Chip label={ollama?.ok === false ? "Ollama offline" : `${ollama?.models?.length ?? 0} local models`} color={ollama?.ok === false ? "warning" : "success"} variant="outlined" /><Button onClick={logout} variant="outlined">Logout</Button></>}
    >
      <Box className="workspace-layout">
        <Card className="chat-surface">
          <Stack direction="row" alignItems="center" spacing={1.2} className="px-5 pt-4">
            <Tabs value={mode} onChange={(_, value) => setMode(value)} variant="scrollable">
              {["ask", "build", "review", "fix", "learn"].map((item) => <Tab key={item} value={item} label={item[0].toUpperCase() + item.slice(1)} />)}
            </Tabs>
            <Box flex={1} />
            <Button onClick={async () => { await api("/api/chat/clear", { method: "POST", body: "{}" }); await reload(); }} startIcon={<Icon>add</Icon>}>New chat</Button>
          </Stack>
          <Divider />
          <Box className="chat-scroll">
            {(chat?.messages || []).map((item, index) => (
              <Box key={index} className={`bubble-row ${item.role === "user" ? "justify-end" : "justify-start"}`}>
                <Paper className={`message-bubble-m3 ${item.role === "user" ? "user-bubble" : ""}`} variant="outlined">
                  <Typography variant="caption" color="text.secondary">{item.role === "assistant" ? item.modelName || "Assistant" : `${state.activeUser.name} · ${item.mode || mode}`}</Typography>
                  <Typography whiteSpace="pre-wrap" mt={0.75}>{item.content}</Typography>
                </Paper>
              </Box>
            ))}
          </Box>
          <Box component="form" onSubmit={send} className="composer-m3">
            <TextField multiline minRows={3} fullWidth label="Message Olla Nest" value={message} onChange={(e) => setMessage(e.target.value)} />
            <Stack direction="row" spacing={1.5} alignItems="center" mt={1.5}>
              <Select size="small" value={manualModelId} onChange={(e) => setManualModelId(e.target.value)} displayEmpty sx={{ minWidth: 220 }}>
                <MenuItem value="">Auto Router</MenuItem>
                {models.map((model) => <MenuItem key={model.id} value={model.id}>{model.name}</MenuItem>)}
              </Select>
              <Box flex={1} />
              <Button type="submit" variant="contained" endIcon={<Icon>send</Icon>}>Send</Button>
            </Stack>
          </Box>
        </Card>
        <Stack spacing={2}>
          <Card><CardContent><Typography variant="h6">Access</Typography><Typography color="text.secondary" mt={1}>{department?.name || "General"} grants plus user/group rights.</Typography><Stack direction="row" gap={1} flexWrap="wrap" mt={2}>{models.map((m) => <Chip key={m.id} size="small" label={m.name} />)}</Stack></CardContent></Card>
          <Card><CardContent><Typography variant="h6">Router</Typography><Typography color="text.secondary" mt={1}>{router}</Typography></CardContent></Card>
          <Card><CardContent><Typography variant="h6">Account</Typography><Typography color="text.secondary" mt={1}>{state.activeUser.email}</Typography><Typography variant="caption" color="text.secondary">Rights: {(state.activeUser.rights || []).join(", ")}</Typography><Stack component="form" spacing={1.5} mt={2} onSubmit={changePassword}><TextField label="Current password" type="password" value={passwords.currentPassword} onChange={(e) => setPasswords({ ...passwords, currentPassword: e.target.value })} /><TextField label="New password" type="password" value={passwords.newPassword} onChange={(e) => setPasswords({ ...passwords, newPassword: e.target.value })} /><Button type="submit" variant="outlined">Change password</Button></Stack>{accountMessage && <Typography mt={1} variant="body2" color="text.secondary">{accountMessage}</Typography>}</CardContent></Card>
        </Stack>
      </Box>
    </Shell>
  );
}

function MetricCard({ icon, label, value }) {
  return <Card><CardContent><Stack direction="row" alignItems="center" spacing={1.5}><Avatar sx={{ bgcolor: "#E8F0FE", color: googleBlue }}><Icon>{icon}</Icon></Avatar><Box><Typography variant="caption" color="text.secondary" fontWeight={800}>{label}</Typography><Typography variant="h5">{value}</Typography></Box></Stack></CardContent></Card>;
}

function AdminPage() {
  const { state, ollama, reload, refreshModels } = useAppState();
  const [form, setForm] = useState({ name: "", email: "", role: "user", departmentId: "dept-general", password: "" });
  const [notice, setNotice] = useState("");

  if (!state) return <LinearProgress />;
  if (state.activeUser.role !== "admin") {
    window.location.href = "/app";
    return null;
  }

  async function logout() {
    await api("/api/auth/logout", { method: "POST", body: "{}" });
    window.location.href = "/login";
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
      body: JSON.stringify(state.settings),
    });
    await reload();
  }

  return (
    <Shell
      title="Company Dashboard"
      subtitle="ADMIN CONTROL"
      nav={[
        { href: "/admin", label: "Dashboard", icon: "dashboard", active: true },
        { href: "/app", label: "Workspace", icon: "chat" },
      ]}
      actions={<><Chip label={ollama?.ok === false ? "Ollama offline" : `${ollama?.models?.length ?? 0} local models`} color={ollama?.ok === false ? "warning" : "success"} variant="outlined" /><Button onClick={logout} variant="outlined">Logout</Button></>}
    >
      <Stack spacing={2}>
        <Box className="metric-grid-react">
          <MetricCard icon="memory" label="Local Models" value={state.models.filter((m) => m.provider === "ollama" && m.status === "available").length} />
          <MetricCard icon="group" label="Users" value={state.users.length} />
          <MetricCard icon="hub" label="Groups" value={state.groups.length} />
          <MetricCard icon="domain" label="Departments" value={state.departments.length} />
        </Box>
        <Box className="admin-grid-react">
          <Card className="span-8"><CardContent><Stack direction="row" alignItems="center" mb={1.5}><Box flex={1}><Typography variant="h6">Available Local Models</Typography><Typography color="text.secondary" variant="body2">Discovered live from Ollama.</Typography></Box><Button onClick={refreshModels} variant="contained" size="small">Refresh</Button></Stack><Box className="model-table">{state.models.filter((m) => m.provider === "ollama").length ? state.models.filter((m) => m.provider === "ollama").map((m) => <Box key={m.id} className="model-row"><Box><Typography fontWeight={750}>{m.name}</Typography><Typography variant="caption" color="text.secondary">{m.model}</Typography></Box><Chip size="small" label={m.status} color={m.status === "available" ? "success" : "default"} /><Typography color="text.secondary" variant="body2">{(m.capabilities || []).join(", ")}</Typography></Box>) : <Paper variant="outlined" className="empty-state"><Icon>cloud_off</Icon><Box><Typography fontWeight={750}>No local models found</Typography><Typography variant="body2" color="text.secondary">Start Ollama on your laptop and click Refresh.</Typography></Box></Paper>}</Box></CardContent></Card>
          <Card className="span-4"><CardContent><Typography variant="h6">Access Model</Typography><Stack spacing={1} mt={1.5}>{["User grants", "Group grants", "Department grants"].map((x) => <Paper key={x} variant="outlined" className="access-tile compact-tile"><Typography fontWeight={700}>{x}</Typography><Typography variant="body2" color="text.secondary">Controls model eligibility.</Typography></Paper>)}<Paper variant="outlined" className="access-tile compact-tile"><Typography fontWeight={700}>Storage</Typography><Typography variant="body2" color="text.secondary">PostgreSQL · MongoDB · Redis</Typography></Paper></Stack></CardContent></Card>
          <Card className="span-5"><CardContent><Typography variant="h6">Create User</Typography><Stack component="form" spacing={1.25} mt={1.5} onSubmit={createUser}><TextField label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="clean-input" /><TextField label="Email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} className="clean-input" /><Select size="small" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}><MenuItem value="user">User</MenuItem><MenuItem value="admin">Admin</MenuItem></Select><Select size="small" value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })}>{state.departments.map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</Select><TextField label="Temporary password" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} className="clean-input" /><Button type="submit" variant="contained">Create User</Button></Stack>{notice && <Alert sx={{ mt: 1.5 }} severity="info">{notice}</Alert>}</CardContent></Card>
          <Card className="span-7"><CardContent><Typography variant="h6">Users</Typography><Stack spacing={1.2} mt={2}>{state.users.map((u) => <Paper key={u.id} variant="outlined" className="user-list-row"><Box flex={1}><Typography fontWeight={750}>{u.name}</Typography><Typography variant="body2" color="text.secondary">{u.email} · {u.role} · {(u.rights || []).join(", ")}</Typography></Box><Chip size="small" color={u.active ? "success" : "default"} label={u.active ? "active" : "inactive"} /><Button size="small" onClick={() => resetPassword(u.id)}>Reset</Button><Button size="small" onClick={() => toggleUser(u)}>{u.active ? "Deactivate" : "Activate"}</Button></Paper>)}</Stack></CardContent></Card>
          <Card className="span-4"><CardContent><Typography variant="h6">System Settings</Typography><Stack mt={1}><FormControlLabel control={<Switch checked={state.settings.routerEnabled} onChange={(e) => state.settings.routerEnabled = e.target.checked} />} label="Auto Router enabled" /><FormControlLabel control={<Switch checked={state.settings.allowApiModels} onChange={(e) => state.settings.allowApiModels = e.target.checked} />} label="Allow API models" /><FormControlLabel control={<Switch checked={state.settings.localOnlyDefault} onChange={(e) => state.settings.localOnlyDefault = e.target.checked} />} label="Local-first by default" /><Button onClick={saveSettings} variant="contained">Save Settings</Button></Stack></CardContent></Card>
          <Card className="span-8"><CardContent><Typography variant="h6">Audit Trail</Typography><Stack mt={2} spacing={1}>{state.audit.length ? state.audit.map((a) => <Box key={a.id} className="timeline-item"><Icon>history</Icon><Box><Typography fontWeight={700}>{a.detail}</Typography><Typography variant="caption" color="text.secondary">{a.actor} · {new Date(a.createdAt).toLocaleString()}</Typography></Box></Box>) : <Typography color="text.secondary">No audit events yet.</Typography>}</Stack></CardContent></Card>
        </Box>
      </Stack>
    </Shell>
  );
}

function Root() {
  const path = window.location.pathname;
  if (path === "/admin") return <AdminPage />;
  if (path === "/app") return <WorkspacePage />;
  return <LoginPage />;
}

createRoot(document.getElementById("root")).render(
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <Root />
  </ThemeProvider>
);
