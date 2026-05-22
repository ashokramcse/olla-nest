# Architecture

## Overview

Olla Nest is a standalone Java Spring Boot web application. The backend is a modular Spring Boot 3.5.3 server with embedded Tomcat. The frontend is plain HTML, CSS, and JavaScript served as static files — no build step, no framework, no bundler required. No Docker. No Node.js.

```
Standalone Java Process (java -jar olla-nest.jar)
├── Spring Boot 3.5.3 (embedded Tomcat)   ← HTTP server, REST API, SSE, WebSocket
│   └── src/main/java/com/ollanest/       ← Controllers, services, filters, config
├── public/                               ← Static frontend (login, workspace, admin)
├── data/olla-nest.sqlite                 ← SQLite database (file, no server)
└── data/backups/                         ← Automated daily backups

Host machine
└── Ollama                                ← Local model inference (http://localhost:11434)
```

---

## Runtime Stack

| Layer | Technology | Purpose |
|---|---|---|
| Runtime | Java 26 + Spring Boot 3.5.3 | Embedded Tomcat HTTP server |
| Frontend | HTML / CSS / Vanilla JS | Served as static files from `public/` |
| Database | SQLite via JDBC (sqlite-jdbc 3.49) | Users, groups, models, permissions, chat, settings |
| Connection pool | HikariCP (pool-size=1) | SQLite single-writer constraint |
| Schema migrations | Flyway | `V1__init.sql` — runs automatically on startup |
| AI inference | Ollama (host) | Local model execution |
| Cloud AI | Anthropic, OpenAI, Groq, Custom | Outbound calls via Java HttpClient |
| Syntax highlighting | highlight.js (vendor) | Client-side code block rendering |
| Markdown | marked.js (vendor) | AI response rendering with custom code renderer |
| XSS sanitisation | DOMPurify (vendor) | Sanitises AI-generated HTML before DOM insertion |
| Terminal | ProcessBuilder → WebSocket | Interactive shell via Spring WebSocket |

### SQLite Configuration

SQLite runs in **WAL (Write-Ahead Logging) mode** with the following pragmas set on every connection:

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA synchronous=NORMAL;
PRAGMA busy_timeout=5000;
```

HikariCP is configured with `maximum-pool-size=1` to respect SQLite's single-writer constraint. All writes are serialised through this single connection.

---

## Source Layout

```
src/main/java/com/ollanest/
├── OllaNestApplication.java              # Spring Boot entry point
│
├── config/
│   ├── AppConfig.java                    # ObjectMapper, global beans
│   ├── SecurityConfig.java               # Spring Security filter chain (custom, no defaults)
│   ├── WebConfig.java                    # Static resource handler (serves ./public/**)
│   ├── WebSocketConfig.java              # WebSocket endpoint + auth interceptor
│   └── WebSocketAuthInterceptor.java     # Validates session cookie before WebSocket handshake
│
├── controller/
│   ├── BaseController.java               # requireAuth(), requireAdmin(), currentUser() helpers
│   ├── AuthController.java               # POST /api/auth/login, /logout; GET /me
│   ├── BootstrapController.java          # GET /api/bootstrap (first-boot detection)
│   ├── ChatController.java               # POST /api/chat, /stream (SSE), /clear, /feedback; DELETE /api/chat
│   ├── ThreadController.java             # GET/DELETE/PATCH /api/threads, /activate, /fork
│   ├── StateController.java              # GET /api/state (full app hydration)
│   ├── AccountController.java            # POST /api/account/password, PATCH /profile, GET /usage
│   ├── WorkspaceController.java          # GET /api/workspace/browse, POST /local-settings
│   ├── PageController.java               # Serves HTML pages (/, /app, /admin, /login)
│   └── admin/
│       ├── AdminUserController.java      # User CRUD, sessions, overrides, effective-access
│       ├── AdminSettingsController.java  # Settings, departments, backup trigger
│       ├── AdminReportsController.java   # Analytics, feedback
│       ├── AdminModelsController.java    # Model governance, Ollama ping
│       ├── AdminProvidersController.java # Provider CRUD, model approval
│       ├── AdminTeamsController.java     # Teams CRUD
│       └── AdminHealthController.java    # Health check, DB stats, JVM info
│
├── filter/
│   ├── SessionAuthFilter.java            # Reads cookie, sets authenticated user on request
│   └── SecurityHeadersFilter.java        # CSP, HSTS, X-Frame-Options, etc.
│
├── service/
│   ├── AuthService.java                  # Session map (in-memory + DB), cookie creation
│   ├── ChatService.java                  # Context building, system prompt, active session management
│   ├── ProviderService.java              # callProvider() / callProviderStream() for all AI providers
│   ├── RouterService.java                # routeModel(), classifyRequest(), detectSensitiveContent()
│   ├── OllamaService.java                # syncOllamaModels() — @Scheduled every 60s
│   ├── WorkspaceService.java             # File workspace management, artifact extraction
│   ├── BackupService.java                # Daily SQLite backup with 7-file rotation
│   ├── CryptoService.java                # AES-256-GCM encrypt/decrypt for API keys
│   ├── DatabaseService.java              # Schema seeding, settings helpers, first-boot init
│   ├── ModelService.java                 # Model parsing and allowed-model resolution
│   ├── UserService.java                  # publicUser(), effectiveAccess(), hasRight()
│   ├── MonitorService.java               # Request counters
│   └── TerminalService.java              # WebSocket shell bridge via ProcessBuilder
│
├── model/
│   ├── User.java                         # User POJO
│   ├── ChatSession.java                  # Chat session POJO
│   ├── ChatMessage.java                  # Chat message POJO
│   └── Model.java                        # AI model POJO
│
└── util/
    └── UrlValidator.java                 # SSRF protection — validates URLs, blocks private IPs

src/main/resources/
├── application.properties               # All config with env var defaults
└── db/migration/
    └── V1__init.sql                     # Full SQLite schema (Flyway managed)

public/                                  # Frontend — unchanged, served as static files
├── app.html / app.js                    # User workspace SPA
├── admin.html / admin.js                # Admin dashboard SPA
├── login.html / login.js                # User login
├── styles.css                           # All application styles
├── theme.js / dropdown.js               # UI utilities
└── vendor/                              # marked, highlight.js, DOMPurify, xterm, chart.js
```

---

## Request Flow

### Standard REST Request
```
Browser → HTTP request
  → SecurityHeadersFilter  (adds CSP, HSTS, X-Frame-Options)
  → SessionAuthFilter       (reads olla_nest_session cookie → attaches user to request)
  → Spring DispatcherServlet
  → Controller              (requireAuth / requireAdmin guard)
  → Service layer           (JdbcTemplate → SQLite)
  → JSON response
```

### SSE Streaming Chat
```
Browser → POST /api/chat/stream
  → Auth filters
  → ChatController.stream()
  → RouterService.routeModel() — classifies + scores candidates
  → SSE: {"type":"routing","model":"llama3.2:3b"}
  → ProviderService.callProviderStream() — streams from Ollama/Anthropic/OpenAI
  → SSE: {"type":"token","content":"..."} × N tokens
  → DB: INSERT user message + assistant message (atomic transaction)
  → SSE: {"type":"done","tokensUsed":N,"messageId":"..."}
```

### WebSocket Terminal
```
Browser → WS /api/terminal
  → WebSocketAuthInterceptor (validates session + workspace:build right)
  → TerminalService.afterConnectionEstablished()
  → ProcessBuilder → /bin/bash -i
  → stdin/stdout bridged bidirectionally to WebSocket frames
```

---

## Database Schema (key tables)

| Table | Purpose |
|---|---|
| `users` | Accounts, roles, departments, access tiers, token limits, expiry |
| `sessions` | Persistent session tokens (in-memory map is primary; DB is fallback) |
| `models` | Available AI models from Ollama sync or approved provider models |
| `api_providers` | External provider configs with AES-encrypted API keys |
| `api_models` | Models approved per provider for router selection |
| `chat_sessions` | Conversation threads (active + archived history) |
| `chat_messages` | Messages with token counts, latency, model used, artifacts |
| `settings` | Key-value platform configuration |
| `audit_events` | Immutable audit trail of all admin and user actions |
| `router_traces` | Per-request routing decisions for observability |
| `feedback` | Thumbs up/down ratings on assistant messages |
| `login_attempts` | Per-IP brute-force protection counters |
| `workspace_prefs` | Per-user workspace folder and permission mode |
| `departments` / `groups` / `teams` | Organisational hierarchy |
| `access_grants` | Fine-grained permission overrides per user/group/team |

---

## Security Architecture

| Layer | Mechanism |
|---|---|
| Authentication | HttpOnly `SameSite=Lax` cookie, 256-bit SecureRandom token |
| Session storage | `ConcurrentHashMap` (primary) + `sessions` DB table (recovery) |
| Password hashing | BCrypt cost factor 12 |
| API key encryption | AES-256-GCM, random 12-byte IV, hex-encoded |
| CSRF protection | `X-Requested-With` header required on all state-changing endpoints |
| Rate limiting | Per-IP login counter in DB; per-user chat counter in memory |
| SSRF protection | `UrlValidator` blocks private/loopback IPs on all provider URLs |
| Path safety | Workspace browse + root restricted to `user.home` / `data/` |
| Terminal auth | WebSocket requires session + `workspace:build` right |
| Response headers | CSP, HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy |
| Sensitive content | SSN, credit card, API key, PHI patterns block external routing |

---

## Background Jobs

| Job | Schedule | Description |
|---|---|---|
| Ollama model sync | Every 60 seconds | Calls `/api/tags`, upserts available models |
| Session cleanup | Every hour | Removes expired sessions from memory + DB |
| SQLite backup | Daily at 02:00 | `VACUUM INTO` backup, keeps last 7 files |
