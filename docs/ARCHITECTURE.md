# Architecture

## Overview

Olla Nest is a **Maven multi-module Spring Boot application** — two independent Spring Boot services sharing a common library module, built from a single parent POM. The frontend is plain HTML, CSS, and JavaScript served as static files. No Docker. No Node.js. No build step for the frontend.

```
Maven Multi-Module Build (mvn clean package)
├── olla-nest-common  (JAR)   ← Shared: all services, models, connectors, filters, config
├── olla-nest-admin   (JAR)   ← Admin control panel   → port 8080
└── olla-nest-user    (JAR)   ← Employee workspace    → port 8081

Runtime — two Java processes, one shared SQLite database
├── java -jar olla-nest-admin-*.jar   (port 8080)
│   └── Spring Boot 3.5.14 + embedded Tomcat
├── java -jar olla-nest-user-*.jar    (port 8081)
│   └── Spring Boot 3.5.14 + embedded Tomcat
├── public/                           ← Static frontend (login, workspace, admin)
├── scripts/
│   ├── whisper_server.py             ← OpenAI-compatible faster-whisper HTTP server (port 8765)
│   ├── start_whisper.sh              ← One-time venv setup — macOS + all Linux distros
│   ├── start_whisper.bat             ← One-time venv setup — Windows CMD
│   └── start_whisper.ps1             ← One-time venv setup — Windows PowerShell / Server
├── data/olla-nest.sqlite             ← Shared SQLite database (WAL mode, file-based)
└── data/backups/                     ← Automated daily VACUUM INTO backups (7-file rotation)

Host machine
├── Ollama                            ← Local LLM inference (http://localhost:11434)
└── faster-whisper server             ← Local STT inference (http://localhost:8765, auto-started)
```

---

## Runtime Stack

| Layer | Technology | Purpose |
|---|---|---|
| Runtime | Java 26 + Spring Boot 3.5.14 | Embedded Tomcat HTTP server |
| Frontend | HTML / CSS / Vanilla JS | Served as static files from `public/` |
| Database | SQLite via JDBC (sqlite-jdbc 3.49) | Users, groups, models, permissions, chat, settings |
| Connection pool | HikariCP (pool-size=1) | SQLite single-writer constraint |
| Schema migrations | Flyway | `V1__init.sql` — runs automatically on startup |
| AI inference | Ollama (host) | Local LLM model execution |
| STT (local, default) | faster-whisper Python server on port 8765 | Free speech-to-text, auto-started by `WhisperServerManager` |
| STT (cloud, optional) | OpenAI Whisper API | Paid STT; selected via `sttProvider=openai` in settings |
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

HikariCP is configured with `maximum-pool-size=1` to respect SQLite's single-writer constraint. All writes are serialised through this single connection. Both the admin and user services connect to the **same** `data/olla-nest.sqlite` file; WAL mode allows concurrent reads from both processes.

---

## Source Layout

```
olla-nest-common/src/main/java/com/ollanest/
│
├── config/
│   ├── AppConfig.java                    # ObjectMapper, virtual thread executor
│   ├── SecurityConfig.java               # Spring Security filter chain (custom, no defaults)
│   ├── WebConfig.java                    # Static resource handler (serves ./public/**)
│   ├── WebSocketConfig.java              # WebSocket endpoint + auth interceptor
│   └── WebSocketAuthInterceptor.java     # Validates session cookie before WebSocket handshake
│
├── controller/
│   ├── BaseController.java               # requireAuth(), requireAdmin(), requireAuthWithCsrf(),
│   │                                     # sanitizeText() — public static for testability
│   ├── AuthController.java               # POST /api/auth/login (rate limit, BCrypt DoS guard,
│   │                                     # SSO bypass prevention, IP audit), /logout, GET /me
│   ├── BootstrapController.java          # GET /api/bootstrap (first-boot detection)
│   ├── ChatController.java               # POST /api/chat/stream (SSE), /clear, /feedback
│   ├── ThreadController.java             # GET/DELETE/PATCH /api/threads
│   ├── StateController.java              # GET /api/state (full app hydration)
│   ├── AccountController.java            # Profile, usage, password change
│   ├── WorkspaceController.java          # File browse, read, write
│   └── PageController.java              # Serves HTML pages (/, /app, /admin, /login)
│
├── filter/
│   ├── MdcLoggingFilter.java             # Per-request MDC: requestId, userId, userEmail,
│   │                                     # userRole, method, path, ip — SOC 2 CC7.2
│   ├── SessionAuthFilter.java            # Reads session cookie (app.session-cookie-name) → sets User on request
│   └── SecurityHeadersFilter.java        # CSP, HSTS (HTTPS only), X-Frame-Options: DENY,
│                                         # X-Content-Type-Options, Referrer-Policy, Permissions-Policy
│
├── service/
│   ├── AuthService.java                  # ConcurrentHashMap session cache + DB persistence
│   ├── BackupService.java                # @Scheduled VACUUM INTO + AtomicBoolean concurrent guard
│   ├── ChatService.java                  # Context assembly, system prompts, appendAudit()
│   ├── CodeSandboxService.java           # ProcessBuilder: Python/JS/Ruby/Java/Bash; 10s kill
│   ├── CryptoService.java                # AES-256-GCM, 12-byte random IV
│   ├── DatabaseService.java              # Flyway V1–V6, seed data, settings helpers
│   ├── DeepResearchService.java          # Plan → Search → Synthesise pipeline
│   ├── EmbeddingService.java             # Ollama embeddings + cosine similarity
│   ├── FunctionCallService.java          # 4 built-in AI tools
│   ├── ImageGenerationService.java       # DALL-E 3 + Stable Diffusion Automatic1111
│   ├── ModelService.java                 # Model access control, allowed-model resolution
│   ├── MonitorService.java               # Atomic request/error counters
│   ├── OllamaService.java                # @Scheduled every 60s — syncOllamaModels()
│   ├── PromptTemplateService.java        # Per-mode Spring AI PromptTemplate system prompts
│   ├── ProviderService.java              # callProvider() / callProviderStream() — Ollama, Claude,
│   │                                     # OpenAI, Groq, custom
│   ├── RagService.java                   # Chunk, embed, cosine retrieve (top-5, threshold 0.30)
│   ├── RouterService.java                # Score all candidates; privacy gate; privacy-aware routing
│   ├── SsoService.java                   # Google OAuth 2.0, generic OIDC, SAML 2.0
│   ├── UserService.java                  # publicUser(), effectiveAccess(), hasRight()
│   ├── VoiceService.java                 # Whisper STT (local + OpenAI) + OpenAI TTS-1
│   ├── WebSearchService.java             # Serper / Brave / SearXNG
│   ├── WhisperServerManager.java         # Auto-starts faster-whisper Python server on port 8765
│   └── WorkspaceService.java             # File I/O, artifact extraction
│
├── model/
│   ├── User.java                         # Full user POJO (30+ fields)
│   ├── ChatSession.java / ChatMessage.java / ModelRecord.java
│
└── util/
    └── UrlValidator.java                 # SSRF protection — DNS resolves, blocks RFC-1918 / loopback

olla-nest-common/src/main/resources/
├── application.properties               # Shared config — datasource, Flyway, scheduler
├── logback-spring.xml                   # CONSOLE (dev) / LOKI_ASYNC (prod) — MDC pattern
└── db/migration/
    ├── V1__init.sql                     # 30-table core schema
    ├── V2__rag.sql                      # rag_documents, rag_chunks
    ├── V3__connectors.sql               # connector_configs, sync_log, documents
    ├── V4__sso.sql                      # sso_providers, oauth_state
    ├── V5__search_images.sql            # Search / image / voice settings columns
    └── V6__performance_indexes.sql      # 17 composite indexes

olla-nest-admin/src/main/java/com/ollanest/controller/admin/
├── AdminUserController.java             # User CRUD, sessions, effective-access, overrides
├── AdminSettingsController.java         # Settings CRUD; workspaceRoot system-path block-list;
│                                        # POST /api/admin/settings/backup → BackupService
├── AdminReportsController.java          # Analytics; ORDER BY enum guard; LIMIT bounds (1–500)
├── AdminModelsController.java           # Model governance; status allow-list validation
├── AdminProvidersController.java        # Provider CRUD, model approval, AES key encryption
├── AdminConnectorController.java        # Connector CRUD, manual sync, test, logs
├── AdminTeamsController.java            # Departments / groups / teams CRUD
└── AdminHealthController.java           # JVM memory, DB stats, uptime, Ollama status

olla-nest-user/src/main/java/com/ollanest/controller/
├── ChatController.java                  # POST /api/chat/stream (SSE), /clear, /feedback
├── ThreadController.java                # GET/DELETE /api/threads
├── AccountController.java               # GET /api/account/profile, /usage; PATCH profile
├── DocumentController.java              # POST /api/documents/upload; GET/DELETE list
├── WorkspaceController.java             # File browse, read, write
├── VoiceController.java                 # POST /api/voice/transcribe, /speak
├── ImageController.java                 # POST /api/images/generate
├── CodeSandboxController.java           # POST /api/sandbox/run
├── SsoController.java                   # /api/auth/sso/authorize, /callback, /saml/acs
└── BootstrapController.java             # GET /api/bootstrap

public/                                  # Frontend — static files served by both apps
├── app.html / app.js                    # Employee workspace SPA
├── admin.html / admin.js                # Admin dashboard SPA
├── login.html / login.js / admin-login.*
├── styles.css                           # All application styles
└── vendor/                              # marked, highlight.js, DOMPurify, xterm, chart.js
```

---

## Request Flow

### Standard REST Request
```
Browser → HTTP request
  → SecurityHeadersFilter  (adds CSP, HSTS, X-Frame-Options)
  → SessionAuthFilter       (reads the per-app session cookie → attaches user to request)
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
| Authentication | HttpOnly `SameSite=Lax` cookie, 256-bit `SecureRandom` token |
| Session storage | `ConcurrentHashMap` (primary) + `sessions` DB table (recovery); hourly sweep |
| Password hashing | BCrypt cost factor 12; dummy sentinel at cost 10 for constant-time response |
| BCrypt DoS prevention | Email > 320 chars or password > 1024 chars rejected before hash (HTTP 400) |
| SSO bypass prevention | `AND auth_provider = 'local'` on login query — SSO users cannot use password path |
| API key encryption | AES-256-GCM, random 12-byte IV per value, hex-encoded |
| SQL injection | `ORDER BY` enum guard, `LIMIT` int-cast + bounds (1–500), table-name allow-list |
| XSS prevention | `BaseController.sanitizeText()` via `HtmlUtils.htmlEscape` on all user text |
| CSRF protection | `X-Requested-With` header required on all non-GET state-changing endpoints |
| Rate limiting | Per-IP login attempts in `login_attempts` DB table (10 / 15 min); per-user chat rate in memory |
| SSRF protection | `UrlValidator` blocks private/loopback IPs on all cloud provider URLs |
| System path protection | `workspaceRoot` blocked from `/etc`, `/bin`, `/proc`, `/sys`, `/dev`, `C:\Windows` |
| Path safety | Workspace browse + root restricted to `user.home` / `data/` |
| Terminal auth | WebSocket requires session + `workspace:build` right |
| Response headers | CSP, HSTS (HTTPS only), `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` |
| MDC logging | `requestId`, `userId`, `userEmail`, `userRole`, `method`, `path`, `ip` on every log line |
| SOC 2 audit trail | `auth.login` (with IP + role), `auth.login.failed` (with IP), `chat.request` in `audit_events` |
| Sensitive content | SSN, credit card, API key, PHI regex patterns block external routing automatically |
| Concurrent backup | `AtomicBoolean` CAS — only one `VACUUM INTO` at a time; concurrent requests rejected |

---

## Background Jobs

| Job | Schedule | Description |
|---|---|---|
| Ollama model sync | Every 60 seconds | `OllamaService` calls `/api/tags`, upserts available models |
| Session cleanup | Every hour | `AuthService` removes expired sessions from `ConcurrentHashMap` + `sessions` table |
| Chat rate-limit sweep | Every 10 minutes | `ChatService` removes stale per-user rate-limit entries from in-memory map |
| SQLite backup | Daily at 03:00 | `BackupService` — `VACUUM INTO` with `AtomicBoolean` concurrent guard, keeps last 7 files |
| Whisper server start | Once on startup | `WhisperServerManager` launches `scripts/whisper_server.py` in daemon background thread |

---

## Voice STT Pipeline

```
Browser (MediaRecorder)
  │  click mic → start recording (WebM/OGG chunks every 250ms)
  │  click mic again → stop → POST /api/voice/transcribe (multipart audio)
  ▼
VoiceService.transcribe()
  │  reads sttProvider setting
  ├─ "local" (default) → POST http://localhost:8765/v1/audio/transcriptions
  │                          (whisper_server.py, faster-whisper, FREE)
  └─ "openai"          → POST https://api.openai.com/v1/audio/transcriptions
                             (paid, $0.006/min)
  ▼
{ "text": "transcribed speech" }  →  inserted into chat input
```

### WhisperServerManager Startup Sequence

1. Spring Boot context initialises `WhisperServerManager` bean
2. Constructor spawns daemon thread `whisper-server-start`
3. Thread sleeps 500ms (allows Spring context to finish wiring)
4. `GET http://localhost:8765/health` — if 200, server already running → skip
5. Walk up directory tree (max 6 levels) to find `scripts/whisper_server.py`
6. Resolve venv Python: `scripts/venv/bin/python` (Unix) or `scripts/venv/Scripts/python.exe` (Windows)
7. If venv absent: log platform-specific setup instructions → return (voice STT unavailable but app continues)
8. `ProcessBuilder` launches Python; stdout/stderr streamed to Spring log via daemon thread `whisper-server-log`
9. On `@PreDestroy`: `SIGTERM` → wait 5s → `SIGKILL`

### Cross-Platform Python Version Policy

`faster-whisper` depends on `av` (PyAV). PyAV has no binary wheels for Python 3.13/3.14 (Cython API incompatible). The setup scripts enforce Python 3.9–3.12, prefer 3.11 (stable LTS, all wheels available), and auto-rebuild the venv if it was created with a 3.13+ interpreter.
