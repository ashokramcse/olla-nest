# Architecture

## Overview

Olla Nest is a single-container web application that runs via Docker. The backend is a modular Node.js Express server split across `src/`. The frontend is plain HTML, CSS, and JavaScript served as static files — no build step required.

```
Docker container
├── Node.js 24 (Express)     ← API, routing logic, auth, Ollama + cloud provider integration
│   └── src/                 ← Modular backend (routes, services, middleware, models, db)
├── public/                  ← Static frontend (login, workspace, admin)
└── /app/data/               ← SQLite database + workspace files (volume mount)

Host machine
└── Ollama                   ← Local model inference (reached via host.docker.internal)
```

---

## Runtime Stack

| Layer | Technology | Purpose |
|---|---|---|
| Runtime | Node.js 24 Alpine | Express API server |
| Frontend | HTML / CSS / JS | Served as static files from `public/` |
| SQL | SQLite (DELETE journal mode) | Users, groups, models, permissions, chat, settings |
| AI inference | Ollama (host) | Local model execution |
| Cloud AI | Anthropic, OpenAI, Groq | Cloud provider calls via provider service |
| Syntax highlighting | highlight.js (vendor) | Client-side code block rendering, 30+ languages |
| Markdown | marked.js (vendor) | AI response markdown rendering with custom code renderer |
| XSS sanitisation | DOMPurify (vendor) | Sanitises all AI-generated HTML before DOM insertion |
| Container | Docker + Docker Compose | Only supported runtime |

> **SQLite journal mode**: WAL mode is intentionally disabled. On Docker-for-Mac and similar virtualised filesystems, WAL produces 0-byte WAL files — writes in one connection are invisible to all others. `PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL` is used instead for reliable cross-connection visibility.

### Docker-Only Runtime Contract

The application is designed to run only in Docker. `server.js` checks for Docker runtime signals (`/.dockerenv` or `OLLA_NEST_DOCKER_RUNTIME=true`) during startup. Host-machine starts are blocked by default.

The Docker image sets `OLLA_NEST_DOCKER_RUNTIME=true`, and `docker-compose.yml` repeats it for clarity. The container starts with `npm run container:start`; the public `npm start` command is intentionally disabled to avoid accidental local runs.

---

## Source Layout

```
src/
├── app.js                     # Express app setup, middleware chain, route mounting
├── config.js                  # Environment variables, defaults, constants
│
├── db/
│   └── index.js               # openSql(), initDatabase(), all CREATE TABLE + migrations
│
├── middleware/
│   ├── auth.js                # parseCookies, sessionUser, requireAuth, requireAdmin,
│   │                          #   hasRight, setSession, logout, forceLogoutUser
│   └── security.js            # checkChatRateLimit, loginAttempts, securityHeaders,
│                              #   enforceDockerRuntime
│
├── models/
│   └── user.js                # publicUser(), USER_SELECT, getUsers, allowedModels,
│                              #   allowedModelIds, effectiveAccess, userOverrides,
│                              #   userGroupIds, roleCatalog, permissionCatalog
│
├── services/
│   ├── router.js              # routeModel() — classify, score, privacy gate
│   │                          # detectSensitiveContent()
│   ├── providers.js           # resolveProvider(), callProvider(), callProviderStream()
│   ├── chat.js                # buildSystemPrompt(), buildContextMessages(),
│   │                          #   getActiveChat(), archiveCurrentChat(), appendAudit(),
│   │                          #   appendTrace(), cleanModelOutput()
│   ├── workspace.js           # workspaceForUser(), writeLocalArtifacts(),
│   │                          #   extractArtifacts()
│   └── backup.js              # runBackup() — daily SQLite VACUUM + file copy
│
└── routes/
    ├── auth.js                # POST /api/auth/login, /api/auth/logout
    ├── state.js               # GET /api/state, /api/ollama/ping, /api/ollama/models
    ├── chat.js                # POST /api/chat, POST /api/chat/stream,
    │                          #   POST /api/chat/clear, DELETE /api/chat,
    │                          #   POST /api/chat/feedback
    ├── threads.js             # GET/PATCH/DELETE /api/threads
    ├── workspace.js           # GET /api/workspace/browse,
    │                          #   POST /api/workspace/local-settings
    ├── account.js             # PATCH /api/account/password, GET /api/account/usage
    ├── pages.js               # HTML page routes with auth redirects
    └── admin/
        ├── users.js           # GET/POST /api/admin (user list + create)
        │                      # PATCH /api/admin/:id
        │                      # GET /api/admin/sessions/active
        │                      # DELETE /api/admin/sessions/user/:userId
        ├── models.js          # /api/admin/models/:id/governance
        │                      # GET /api/admin/ollama/ping
        ├── providers.js       # GET/POST/DELETE/PATCH /api/admin/providers
        │                      # POST /api/admin/providers/:id/sync
        │                      # POST /api/admin/providers/:id/test
        │                      # POST/DELETE /api/admin/providers/:id/models/:modelId/approve
        ├── settings.js        # POST /api/admin/settings
        │                      # GET/PATCH /api/admin/departments/:id/rights
        │                      # POST /api/admin/settings/backup
        ├── reports.js         # GET /api/admin/reports, /api/admin/feedback
        ├── teams.js           # /api/admin/teams
        ├── overrides.js       # /api/admin/overrides
        └── health.js          # GET /api/admin/health
```

---

## Request Flow

```
Browser
  │
  ▼
Express (src/app.js)
  │
  ├── Security headers (HSTS, CSP, X-Frame-Options…)
  ├── JSON body parser
  ├── Static file serving (public/)
  │
  ├── /api/auth/*        → login / logout
  ├── /api/state         → user state, models, settings, session list
  ├── /api/chat          → blocking chat (legacy fallback)
  ├── /api/chat/stream   → SSE streaming chat (primary endpoint)
  ├── /api/threads       → chat thread CRUD
  ├── /api/workspace/*   → browse + local-settings
  ├── /api/account/*     → password change, usage stats
  ├── /api/admin/*       → admin CRUD, settings, reports, providers
  │
  └── Page routes        → HTML pages with session-based redirects
```

### SSE Streaming Flow

```
POST /api/chat/stream
  │
  ├── requireAuth + CSRF check
  ├── Daily token quota check
  ├── routeModel() → picks best approved model
  │
  ├── SSE: data: {"type":"routing", "model":"...", "reason":"..."}
  │
  ├── callProviderStream() → opens connection to Ollama or cloud provider
  │   Each token callback:
  │   └── SSE: data: {"type":"token", "content":"..."}
  │
  ├── cleanModelOutput() → strip think-block artifacts
  ├── extractArtifacts() → detect generated files in response
  ├── writeLocalArtifacts() → optionally write to workspace folder
  │
  ├── SQLite transaction:
  │   ├── INSERT chat_messages (user)
  │   └── INSERT chat_messages (assistant, with tokensUsed, latencyMs)
  │
  └── SSE: data: {"type":"done", "tokensUsed":N, "latencyMs":N,
                  "messageId":"...", "artifacts":[...], "extractedFiles":[...]}
```

---

## Auto Router

The router (`src/services/router.js`) runs on every `/api/chat` and `/api/chat/stream` request:

1. **Classify** — detect request type from message text and mode (coding, writing, OCR, medical, review, general)
2. **Filter** — collect models the user is approved to access via user, group, and department grants
3. **Score** — rank each candidate: capability match × 35pts, specialist bonus × 45pts, speed weight, quality weight, privacy score
4. **Select** — pick the highest-scoring approved model
5. **Privacy gate** — SSN / credit card / PHI patterns detected → forced local-only, regardless of user selection
6. **Generate** — call provider (Ollama or cloud) with mode-specific system prompt + project knowledge + sliding-window history

---

## System Prompt Pipeline

Every chat request builds the system prompt in this order:

```
[Base mode instruction (ask / build / fix / review / learn…)]
  + Routing context (selected model + reason)
  + Project Knowledge (admin-configured, if set)
  + Workspace context (active folder, if workspace enabled)
  ──────────────────────────────────────────────────────────
  → Sent as the "system" role message to the model
  → Followed by sliding-window chat history (newest → oldest, within token budget)
  → Followed by the current user message (+ images if vision model)
```

**Project Knowledge** is free-text set by admins in Admin → Settings. It is injected into every prompt for every user — ideal for tech stack context, coding conventions, and team terminology.

---

## Chat Streaming UX (Client Side)

The browser uses the `ReadableStream` API to consume SSE:

```javascript
const res = await fetch("/api/chat/stream", { method: "POST", ... });
const reader = res.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  // parse SSE lines from buffer
  // "routing" → show model name in Thinking phase badge
  // "token"   → append to accumulated text, re-render markdown
  // "done"    → finalise bubble, show elapsed time + file chips
  // "error"   → show error in red badge inline
}
```

Visual phases:

| Phase | Trigger | Badge colour | UI state |
|---|---|---|---|
| Routing | Request sent | Grey | Animated dots, "Finding the best model…" |
| Thinking | `routing` event | Blue | Model name + dots, "Generating response…" |
| Writing | First `token` | Green | Live tokens + blinking cursor |
| Done | `done` event | — | Full markdown, footer chips, avatar settled |
| Error | `error` event | Red | Error message inline |

---

## Code Rendering Pipeline

AI responses flow through a custom `renderMarkdown()` pipeline before reaching the DOM:

```
Raw model output (string)
  → cleanModelOutput() strips <think>…</think> blocks
  → marked.js parser with custom renderer.code()
      → highlight.js syntax highlighting (30+ languages)
      → Line number table-cell layout
      → Diff detection (+ / - / @@ line prefixes)
      → Filename header extraction (// filename: comment)
      → Language badge with colour map
      → ⛶ View + Copy + Run button injection
  → DOMPurify sanitisation (XSS prevention)
  → innerHTML insertion
```

---

## Data Layer

### SQLite (single source of truth)

All data lives in a single SQLite database at `/app/data/olla-nest.sqlite`.

| Table | Contents |
|---|---|
| `users` | Accounts, profile, password hash, role, rights, department, AI quotas, security status |
| `groups` | Named access groups |
| `departments` | Company departments with default rights |
| `user_groups` | User-to-group membership |
| `models` | Discovered Ollama models — scores, capabilities, governance status, context size |
| `api_providers` | Cloud provider configs (type, URL, encrypted API key) |
| `api_models` | Models synced from cloud providers (with context window, approval status) |
| `access_grants` | Model access by user / group / department |
| `role_catalog` | Enterprise RBAC role templates |
| `permission_catalog` | Granular AI usage, admin, model, and workspace permissions |
| `user_overrides` | Per-user access overrides that outrank department and role defaults |
| `chat_sessions` | Per-user chat threads (active / archived, title, timestamps) |
| `chat_messages` | All messages with model attribution, routing reason, latency, token counts |
| `sessions` | Auth session tokens (HttpOnly cookie, 12-hour expiry) |
| `audit_events` | Rolling admin action log |
| `router_traces` | Routing decision log per chat turn |
| `workspace_prefs` | Per-user workspace root, output folder, permission mode |
| `feedback` | Thumbs-up/down ratings on assistant messages |
| `settings` | Key-value config (router, Ollama URL, workspace root, project knowledge…) |

### Key Settings

| Key | Purpose |
|---|---|
| `routerEnabled` | Enable / disable Auto Router |
| `ollama_url` | URL to reach Ollama from the container |
| `workspaceRoot` | Default workspace folder path |
| `projectKnowledge` | Admin-set context injected into every chat prompt |
| `localOnlyDefault` | Route to local models by default |
| `allowApiModels` | Allow cloud provider models |
| `localWritesEnabled` | Allow Build/Fix to write files to workspace |

---

## Authentication

- Sessions use a 256-bit secure random token stored in an `HttpOnly`, `SameSite=Lax`, 12-hour cookie
- Passwords hashed with bcrypt cost factor 12
- CSRF guard: all non-GET API requests require `X-Requested-With: XMLHttpRequest`
- Login rate-limited: 10 failed attempts per IP per 15 minutes
- Session fixation prevented: old token deleted on every new login

---

## Enterprise Access Model

Access is evaluated in this priority order:

1. User override
2. Department policy
3. Role permission template
4. Organisation default

The data foundation (users, roles, permission catalog, model governance, overrides) is fully implemented. Future enterprise connectors (SSO, SCIM, LDAP) should attach to this layer rather than bypass it.

---

## Local File Output

Build and Fix modes can write generated files to the host filesystem via the Docker volume:

```
Container: /app/data/workspace/
Volume:    app-data → /app/data/
```

Permission modes:

| Mode | Behaviour |
|---|---|
| `default` | User must check "Write to workspace" per Build/Fix request |
| `review` | Same as default — approval visible and required |
| `full` | Files written automatically on every Build/Fix request |

---

## Production Database Direction

The current release uses SQLite for simplicity and Docker portability. The intended production architecture for company-scale deployment:

| Layer | Production target | Reason |
|---|---|---|
| SQL | PostgreSQL + pgvector | Multi-user, RBAC, RAG embeddings, audit at scale |
| Document | MongoDB | Flexible AI artifacts, chat history, tool outputs |
| Realtime | Redis | Token streaming, sessions, rate limiting, pub/sub |

---

## Product Stack Direction

| Layer | Current | Target |
|---|---|---|
| Backend | Node.js JS | Node.js TypeScript |
| Frontend | Docker-served HTML/CSS/JS | React + Vite served through Docker |
| Desktop | — | Tauri wrapper around the Docker-backed web UI |
| Mobile | — | React Native / Expo against the same APIs |
| SQL | SQLite | PostgreSQL + pgvector |
| Realtime | — | Redis |
| Auth | Cookie sessions | SSO / OIDC |
