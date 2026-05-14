# Architecture

## Overview

Olla Nest is a single-container web application that runs via Docker. The backend is a Node.js Express server. The frontend is plain HTML, CSS, and JavaScript served as static files — no build step required.

```
Docker container
├── Node.js 24 (Express)     ← API, routing logic, auth, Ollama integration
├── public/                  ← Static frontend (login, workspace, admin)
└── /app/data/               ← SQLite database + JSON document store (volume mount)

Host machine
└── Ollama                   ← Local model inference (reached via host.docker.internal)
```

---

## Runtime Stack

| Layer | Technology | Purpose |
|---|---|---|
| Runtime | Node.js 24 Alpine | Express API server |
| Frontend | HTML / CSS / JS | Served as static files from `public/` |
| SQL | SQLite (`node:sqlite`) | Users, groups, models, permissions, settings |
| Document | JSON file | Chat history, audit log, router traces, workspace preferences |
| AI inference | Ollama (host) | Local model execution |
| Container | Docker + Docker Compose | Only supported runtime |

### Docker-Only Runtime Contract

The application is designed to run only in Docker. `server.js` checks for Docker runtime signals (`/.dockerenv` or `OLLA_NEST_DOCKER_RUNTIME=true`) during startup. Host-machine starts are blocked by default so production, demos, and contributor testing all use the same container path.

The Docker image sets `OLLA_NEST_DOCKER_RUNTIME=true`, and `docker-compose.yml` repeats it for clarity. The container starts with `npm run container:start`; the public `npm start` command is intentionally disabled to avoid accidental local runs.

---

## Request Flow

```
Browser
  │
  ▼
Express (server.js)
  │
  ├── Auth middleware (cookie session)
  │
  ├── /api/state        → load user, models, settings, chat
  ├── /api/chat         → classify → route → Ollama → response → optional file write
  ├── /api/ollama/*     → model discovery and sync
  ├── /api/admin/*      → user management, RBAC, settings, model governance, model sources
  └── /api/account/*    → password change
  │
  ▼
Ollama (host:11434)
  └── /api/generate     → model inference
```

---

## Auto Router

The router runs on every `/api/chat` request:

1. **Classify** — detect request type from message text and mode (coding, writing, OCR, medical, review, general)
2. **Filter** — collect models the user is approved to access via user, group, and department grants
3. **Score** — rank each candidate by: capability match × 35pts, specialist bonus × 45pts, speed weight, quality weight, privacy score
4. **Select** — pick the highest-scoring approved model
5. **Generate** — call Ollama `/api/generate` with a mode-specific system prompt

---

## Data Layer

### SQLite (structural source of truth)

All relational data lives in a single SQLite database at `/app/data/olla-nest.sqlite`.

| Table | Contents |
|---|---|
| `users` | Accounts, profile fields, password hash, role, rights, department, AI access tier, quotas, security status |
| `groups` | Named access groups |
| `departments` | Company departments |
| `user_groups` | User-to-group membership |
| `models` | Discovered Ollama models with scores, capabilities, governance status, sensitivity, runtime limits |
| `access_grants` | Model access by user / group / department |
| `role_catalog` | Enterprise RBAC role templates and permission sets |
| `permission_catalog` | AI usage, admin, model, workflow, and infrastructure permissions |
| `user_overrides` | Individual user access overrides that outrank department and role defaults |
| `settings` | Key-value configuration (router toggle, Ollama URL, workspace root, etc.) |

### JSON Document Store (cognitive archive)

Flexible document data lives in `/app/data/documents.json`:

| Key | Contents |
|---|---|
| `chats` | Per-user chat history with messages, model attribution, artifacts |
| `audit` | Rolling 200-event audit log (actor, action, detail, timestamp) |
| `routerTraces` | Rolling 200-trace log of routing decisions |
| `workspacePrefs` | Per-user workspace folder and permission mode |

---

## Authentication

- Sessions use a secure random token stored in an `HttpOnly`, `SameSite=Lax` cookie
- Sessions expire after 12 hours
- Passwords are hashed with bcrypt (cost factor 12)
- No external auth provider in MVP — admin creates accounts manually

## Enterprise Access Model

Access is evaluated in this order:

1. User override
2. Department policy
3. Role permission template
4. Organization default

The current MVP implements the data foundation and admin screens for users, roles, permissions, model governance, and overrides. Future enterprise connectors such as SSO, SCIM, LDAP, Google Workspace, and Microsoft Entra should attach to the same access model instead of bypassing it.

---

## Local File Output

Build and Fix modes can write generated files to the host filesystem via the Docker volume:

```
Container: /app/data/workspace/olla-nest-output/
Volume:    app-data → /app/data/
```

Users configure their workspace path from the workspace panel. Admins set the company default from Settings.

Permission modes:

| Mode | Behaviour |
|---|---|
| `default` | User must check "Write to workspace" on each Build/Fix request |
| `review` | Same as default — approval visible and required |
| `full` | Files are written automatically on every Build/Fix request |

---

## Production Database Direction

The current MVP uses SQLite and JSON for simplicity. The intended production architecture for company-scale deployment is:

| Layer | Production target | Reason |
|---|---|---|
| SQL | PostgreSQL + pgvector | Multi-user, RBAC, RAG embeddings, audit at scale |
| Document | MongoDB | Flexible AI artifacts, chat history, tool outputs |
| Realtime | Redis | Token streaming, sessions, rate limiting, pub/sub |

These are not used in the current Docker image. The `infra/postgres/init.sql` file contains the schema foundation for the future PostgreSQL migration.

---

## Product Stack Direction

| Layer | Current | Target |
|---|---|---|
| Backend | Node.js JS | Node.js TypeScript |
| Frontend | Docker-served HTML/CSS/JS | React + Vite served only through Docker |
| Desktop | — | Tauri wrapper around the Docker-backed web UI |
| Mobile | — | React Native / Expo against the same APIs |
| SQL | SQLite | PostgreSQL + pgvector |
| Document | JSON | MongoDB |
| Realtime | In-memory | Redis |
| Auth | Simple session | SSO / OIDC |
