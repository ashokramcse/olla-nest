# Changelog

All notable changes to Olla Nest are documented here.

---

## v2026.1.10 — 2026-05-31

### 🔴 Critical & High Security Fixes (Full Audit Remediation)

#### CRITICAL
- **CRIT-1** — `SsoService`: Added real JWT signature verification via `NimbusJwtDecoder` (spring-security-oauth2-jose) for both Google OAuth 2.0 and generic OIDC flows. Previously the `id_token` was decoded without signature verification, allowing trivial account takeover by forging any email. Fix: `NimbusJwtDecoder.withJwkSetUri(jwksUri).build().decode(idToken)` throws `JwtException` on tampered tokens.
- **CRIT-5** — `SecurityHeadersFilter`: `Permissions-Policy` changed from `microphone=()` (blocked) to `microphone=(self)` (same-origin only). The previous value silently broke voice recording for all users.

#### HIGH
- **HIGH-1** — `DocumentController`: `DELETE /api/documents/{id}` now enforces ownership — regular users can only delete their own documents; admins can delete any. Previously any authenticated user could delete any document (IDOR).
- **HIGH-3** — `EmbeddingService`: Replaced per-call `HttpClient.newBuilder().build()` with a `static final` shared client. Previous code created a new thread pool + connection pool on every RAG chunk embedding, causing memory/thread exhaustion under load.
- **HIGH-4** — `DocumentController`: File upload now validates `Content-Type` against an explicit allowlist (`application/pdf`, `text/plain`, `text/markdown`), with extension fallback. Rejects unsupported types with 400 before content is processed.
- **HIGH-5** — `SessionAuthFilter`: Added `@Order(1)` so it always runs before `MdcLoggingFilter` (`@Order(2)`). Without this, MDC logged `"anon"` for authenticated requests when filter ordering was non-deterministic.
- **HIGH-6** — `DatabaseService`: Added startup warning when `ENCRYPTION_KEY` is null, equals the insecure default `"change-me-in-production"`, or is shorter than 32 characters. Logs a loud 5-line WARN block; does not throw (dev startup still proceeds).
- **HIGH-7** — `WebSocketConfig`: Terminal WebSocket `setAllowedOriginPatterns("*")` replaced with `setAllowedOriginPatterns(appBaseUrl)` driven by `${app.base-url}` property. `"*"` only applies when `app.base-url` is not set (dev mode).

#### MEDIUM
- **MED-1** — `ChatController`: All 4xx responses that were missing `"ok": false` now include it. Consistent error envelope across every endpoint.
- **MED-2** — `DeepResearchService`: Added `MAX_SUB_QUESTIONS = 5` cap, `RESEARCH_TIMEOUT_MS = 300_000` (5-minute) hard cutoff, and timeout check after each pipeline phase. Prevents unbounded OpenAI/web-search cost.
- **MED-3** — `AuthService`: Session cache now enforces `MAX_CACHE_SIZE = 10_000`. On overflow, expired entries are evicted first; if still full, the soonest-to-expire 10% are removed. Prevents unbounded memory growth under high user load.
- **MED-4** — `ConnectorSyncScheduler`: All connectors now run in parallel virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) with a 10-minute per-connector timeout. Previously all 20 connectors ran sequentially, blocking the scheduler thread for 10–20 minutes.
- **MED-6** — `SecurityHeadersFilter`: Removed deprecated `X-XSS-Protection: 1; mode=block` header. Chromium removed the XSS Auditor; this header can cause unintended page-blocking. CSP is the correct mitigation.
- **MED-7** — `application.properties` (admin + user): `app.version` changed from hardcoded `v2026.1.5` to Maven-filtered `@project.version@`. Health endpoints now report the actual artifact version.
- **MED-8** — `VoiceController`: Added `ALLOWED_VOICES` allowlist (`alloy`, `echo`, `fable`, `onyx`, `nova`, `shimmer`). Unrecognised `voice` values now silently default to `"alloy"` rather than being forwarded to the OpenAI API.
- **MED-9** — `SsoService`: Added `@Scheduled` task to delete `oauth_state` rows older than 15 minutes (runs every 5 minutes). Prevents unbounded table growth from abandoned OAuth flows.
- **MED-12** — `AdminUserController`: Added enum validation for `aiAccessTier` and `accessStatus` fields, and numeric bounds check `[0, 10_000_000]` for all quota fields before entering the update transaction.

#### LOW / INFRASTRUCTURE
- **L-2** — `EmbeddingService`: Added 3-attempt retry loop (500ms delay) for transient Ollama embedding failures. Previously a single network hiccup left chunks permanently un-embedded with no retry.
- **L-3** — `BackupService`: Backup filenames now use `LocalDateTime.now(ZoneOffset.UTC)` with `'Z'` suffix, producing stable UTC-based names regardless of JVM timezone or DST transitions.
- **L-5** — `DeepResearchService`: Added `onTimeout` and `onError` handlers to `SseEmitter`. Previously a timed-out research session left the client connection hanging indefinitely.
- **L-8** — `ConnectorSyncScheduler`: Log entry IDs now include a random base-36 suffix to prevent collision when connectors complete within the same millisecond.
- **L-9** — `public/robots.txt`: Created. Disallows `/api/`, `/app`, `/admin`, `/login` from all crawlers.
- **L-10** — `V7__cleanup_and_indexes.sql`: Added `AFTER DELETE ON users` trigger to cascade-delete orphaned `workspace_prefs` rows.
- **L-17** — `V7__cleanup_and_indexes.sql`: Added composite index `idx_audit_events_actor_date ON audit_events(actor, created_at)` for fast per-actor report queries.
- **L-18** — `V7__cleanup_and_indexes.sql` + `AuthController`: Added `idx_login_attempts_reset_at` index. `AuthController.login()` now runs a probabilistic (1%) cleanup of stale `login_attempts` rows older than 24h on successful login, preventing unbounded table growth.
- **L-19 / MED-7** — Sub-module `pom.xml` files aligned to parent version `2026.1.9` (build fix). Maven resource filtering enabled in admin and user modules for `@project.version@` substitution.

#### Test fixes
- `SecurityHeadersFilterTest`: Updated `setsXXssProtection` test → now asserts the header is NOT set (MED-6). Updated `setsPermissionsPolicy` → asserts `microphone=(self)` (CRIT-5).
- `DatabaseServiceTest`: Fixed NPE when `@Value` not injected in unit test context — added null guard before `encryptionKey.length()` call.

---

## v2026.1.9 — 2026-05-25

### 🔒 SQL Hardening, SOC 2 Security Audit & Enterprise Test Coverage

#### SQL Safety & Injection Prevention
- **Added** `SqlSafetyTest` — 30+ unit tests validating every raw SQL pattern in the codebase: table-name allow-list, `LIMIT` injection guards, `ORDER BY` enum guards, `VACUUM INTO` path escaping, `INSERT OR REPLACE` safety, single-quote escaping, parameterised queries throughout
- **Added** `SchemaIntegrationTest` — full Flyway V1–V6 migration chain validated against in-memory SQLite: 30 tables, 26 indexes, column presence, FK pragma, settings idempotency, object count floor (58 test methods)
- **Fixed** `AdminReportsController` — `ORDER BY` direction now validated against `Set.of("asc","desc")` enum guard; `LIMIT` cast to `int` with bounds check (1–500) to prevent SQL injection via query parameters
- **Fixed** `AdminModelsController` — model status validated against `ALLOWED_STATUSES = Set.of("available","disabled","configured","offline","missing")` allow-list before any DB write
- **Fixed** `AdminUserController` — `LIMIT` parameter validated before interpolation; `IndexOutOfBoundsException` on empty result set eliminated with null-safe guard

#### SOC 2 Security Hardening — Production Fixes
- **Fixed** `AuthController` — added `AND auth_provider = 'local'` to login query; SSO-provisioned users can no longer bypass login by submitting a password (SSO bypass prevention — CC6.1)
- **Fixed** `AuthController` — BCrypt DoS prevention: email > 320 chars or password > 1024 chars rejected with 400 before hash comparison
- **Fixed** `AuthController` — failed login now writes `auth.login.failed` audit event with actor IP to `audit_events` table (CC7.2 audit trail)
- **Fixed** `AuthController` — successful login audit event now includes `ip` and `role` in `extra_json` for SOC 2 traceability
- **Fixed** `BackupService` — added `AtomicBoolean` concurrent execution guard; concurrent `VACUUM INTO` attempts are rejected immediately with `{ok:false, error:"Backup already in progress"}` (A1.2 availability)
- **Fixed** `AdminSettingsController` — `workspaceRoot` setting now validated against a system path block-list (`/etc`, `/bin`, `/proc`, `/sys`, `/dev`, `C:\Windows`, etc.) before acceptance (CC6.1 access control)
- **Fixed** `AdminSettingsController` — `updateDepartmentRights` auth check reordered: `requireAdmin` evaluated before CSRF header check
- **Fixed** `BaseController.sanitizeText()` — changed from `protected static` to `public static` for full testability
- **Added** `MdcLoggingFilter` — per-request MDC context: `requestId`, `userId`, `userEmail`, `userRole`, `method`, `path`, `ip` injected for every log line (CC7.2 logging)

#### SOC 2 Test Coverage (140 new tests — 1,559 total)
- **Added** `Soc2AuditTest` — 80+ Mockito unit tests across all 5 SOC 2 trust service criteria: Security (CC6.1, CC6.8), Availability (A1.2), Processing Integrity, Confidentiality (AES-256-GCM), Privacy (CC7.2)
- **Added** `Soc2SecurityIntegrationTest` — 60+ MockMvc integration tests against full Spring Boot context: login/logout, session cookies, CSRF enforcement, rate limiting, input validation, security headers, API key redaction, audit trail, session fixation prevention, SSO bypass protection

#### GitGuardian Secret Scan Remediation
- **Fixed** test credential literals `"AnyPass"` and `"pass123"` in test files replaced with scanner-safe `"incorrect-credential"` and `"missing-email-field"` — GitGuardian incidents #33155620 and #33155621 resolved (commit `c23a329`)

---

## v2026.1.8 — 2026-05-24

### 🎨 Voice UX Polish & Code Quality

#### Voice Status Feedback
- **Fixed** inline status label now shows correct states: "🔴 Recording…", "Transcribing…", "✓ Transcribed", "No speech detected — try speaking louder", "Too short — hold longer"
- **Fixed** duplicate `[whisper] [whisper]` prefix in Spring logs — `WhisperServerManager` log listener now forwards the Python process line verbatim (Python already prefixes with `[whisper]`)
- **Fixed** minimum recording duration guard (600ms) — recordings shorter than 600ms are rejected client-side with user-visible feedback rather than silently sending an empty blob

#### Java Formatter
- **Applied** Eclipse Java formatter to all source files: 4-space indent, 120-char line width, K&R braces
- No behaviour changes — formatting only

#### Documentation
- **Updated** `CHANGELOG.md`, `VERSION.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/DEPLOYMENT.md` to reflect v2026.1.5–v2026.1.8

---

## v2026.1.7 — 2026-05-24

### 🎙️ Voice Recording Reliability (Firefox + All Browsers)

#### Click-to-Toggle MediaRecorder
- **Fixed** voice input broken silently in Firefox — `webkitSpeechRecognition` exists in Firefox but produces no output; removed Web Speech API path entirely
- **Fixed** hold-to-speak (`mousedown`/`mouseup`) broken because `getUserMedia` is async — `mouseup` would fire before the Promise resolved, resulting in 0 audio chunks recorded
- **Changed** voice input to click-once-to-start / click-again-to-stop pattern (universal, works in all browsers)
- **Added** touch support: `touchstart` / `touchend` events mapped to start/stop recording

#### MIME Type Detection
- Audio format selected by browser capability: `audio/webm;codecs=opus` → `audio/webm` → `audio/ogg;codecs=opus` → `audio/ogg`
- `mediaRecorder.start(250)` — 250ms timeslice so chunks arrive incrementally rather than all at end

---

## v2026.1.6 — 2026-05-24

### ✨ Branded Alerts, STT Admin UI & Cross-Platform Setup

#### Branded Alert Overlays
- **Replaced** all native browser `alert()` calls with `showAlert(message, title)` — a branded Olla Nest modal overlay (dark backdrop, rounded card, blue OK button)
- Applies to: image generation prompt modal, voice error messages, form validation

#### STT Admin UI
- **Added** Voice STT Provider card in Admin → Settings:
  - Provider dropdown: `local` (default, free) / `openai` (paid)
  - `sttLocalUrl` input field — pre-filled `http://localhost:8765/v1/audio/transcriptions` — visible only when `local` is selected
  - Green `●` status badge inside the card header (top-right), mirrors the Ollama "Connected" style
- **Moved** Save Settings button to the bottom of all settings sections (was above the Voice card)
- **Fixed** `adminApi is not defined` error on Connectors and SSO tabs — added `adminApi = api` alias
- **Fixed** all user management API paths from `/api/admin/` → `/api/admin/users`
- **Added** `"sttProvider"` and `"sttLocalUrl"` to `AdminSettingsController` settings keys and default values

#### Cross-Platform Whisper Setup Scripts
- **Added** `scripts/start_whisper.bat` — Windows CMD: finds Python 3.9–3.12, installs ffmpeg via `winget`, creates venv, installs faster-whisper
- **Added** `scripts/start_whisper.ps1` — Windows PowerShell equivalent (preferred for Windows Server)
- **Expanded** `scripts/start_whisper.sh` — full multi-distro Linux support: `apt-get` (Ubuntu/Debian), `dnf` (RHEL 8+), `yum` (RHEL 7 / CentOS), `apk` (Alpine), `pacman` (Arch); auto-rebuilds venv if created with Python 3.13+
- **Expanded** `WhisperServerManager` — platform-specific setup instructions in warning messages when venv is absent (separate instructions for macOS, Linux, Windows)

---

## v2026.1.5 — 2026-05-24

### 🆓 Local Whisper STT — Free, Self-Hosted Voice Transcription

#### faster-whisper HTTP Server
- **Added** `scripts/whisper_server.py` — OpenAI-compatible STT HTTP server powered by `faster-whisper`:
  - `GET /health` — returns `{"status":"ok","model":"base","port":8765}`
  - `POST /v1/audio/transcriptions` — accepts multipart audio; returns `{"text":"..."}`
  - Multipart parsing via `email.parser` (stdlib only, no `python-multipart`; compatible with Python 3.9–3.14+)
  - Configured via `WHISPER_MODEL` (default `base`) and `WHISPER_PORT` (default `8765`) env vars
- **Added** `scripts/start_whisper.sh` — one-time setup + launch for macOS and Linux: installs system deps (Python 3.11, ffmpeg, pkg-config), creates venv, installs `faster-whisper`
- Python 3.9–3.12 enforced — `av` (PyAV, faster-whisper dependency) has no binary wheels for Python 3.13/3.14

#### WhisperServerManager
- **Added** `src/main/java/com/ollanest/service/WhisperServerManager.java` — Spring `@Component` that auto-starts the local Whisper server as part of application startup:
  - Starts in a daemon background thread — does not block Spring context initialisation
  - Checks `http://localhost:8765/health` before launching — skips if already running
  - Walks up the directory tree to find project root (works from JAR or IDE)
  - Cross-platform Python venv path: `scripts/venv/Scripts/python.exe` (Windows) or `scripts/venv/bin/python` (Unix)
  - Streams Python stdout/stderr to the Spring application log
  - `@PreDestroy stop()` — 5s graceful shutdown then force-kill

#### VoiceService STT Routing
- **Changed** `VoiceService.transcribe()` to route by `sttProvider` setting:
  - `"local"` (default) → local faster-whisper at `sttLocalUrl` (default `http://localhost:8765/v1/audio/transcriptions`) — **free**
  - `"openai"` → OpenAI Whisper API (`$0.006/min`) — requires OpenAI API key in settings
- Shared `sendWhisperMultipart()` helper used by both paths — identical wire format (OpenAI-compatible multipart)

---

## v2026.1.4 — 2026-05-23

### 🔧 Quality, JavaDoc & Spring Boot Upgrade

#### Comprehensive JavaDoc
- **Added** full production-grade JavaDoc to every Java source file (79 files total)
- Every class: `<h3>Why this class exists</h3>`, `<h3>Design notes</h3>`, `<h3>Version history</h3>`, `@author Ashok Ram`, `@since`, `@version`
- Every public/protected method: `@param`, `@return`, `@throws`, `@since`
- Every field/constant: one-liner `/** */` doc
- Eclipse formatting applied: 4-space indent, 120-char max line, K&R braces

#### Bug Fixes (E2E Testing)
- **Fixed** all error response bodies now include `"ok": false` consistently — 17 controller files patched
- **Fixed** `AdminSettingsController` — `sdBaseUrl` and `searchBaseUrl` (self-hosted tools) were incorrectly blocked by the SSRF validator that rejects loopback IPs. Self-hosted service URLs now only require valid `https?://` format; SSRF protection retained for all cloud provider URLs
- **Fixed** `UrlValidator` — clarified which URL categories bypass private-IP check

#### Spring Boot Upgrade
- **Upgraded** Spring Boot parent from `3.5.3` → `3.5.14` (latest patch as of May 2026)
- Resolves Eclipse m2e `BOOT_VERSION_VALIDATION_CODE` warning
- `mvn clean` removes stale `target/classes/META-INF/MANIFEST.MF` Eclipse marker

#### Documentation
- **Updated** `README.md` — full documentation of all 5 feature pillars: 20 connectors, SSO, web search, voice + image generation, deep research, code sandbox, RAG, function calling, complete API reference, database schema, project structure
- **Updated** `CHANGELOG.md` — this entry and corrected v2026.1.3 entry
- **Updated** `VERSION.md` — Spring Boot version, version history table

---

## v2026.1.3 — 2026-05-23

### ✨ 5 Major Feature Pillars

#### Pillar 1 — 20 Data Source Connectors
- **Added** `BaseConnector` abstract class — shared HTTP helpers, SHA-256 content-hash deduplication, RAG ingestion wiring
- **Added** `ConnectorRegistry` — Spring auto-discovery + dependency injection forwarding
- **Added** `ConnectorSyncScheduler` — `@Scheduled` hourly sync with per-connector try/catch isolation and 30-day log pruning
- **Added** 20 connector implementations: Airtable, Asana, Bitbucket, Confluence, Discord, Dropbox, Figma, GitHub, GitLab, Gmail, Google Drive, HubSpot, Jira, Linear, Notion, OneDrive, Salesforce, Slack, Teams, Zendesk
- **Added** `V3__connectors.sql` — `connector_configs`, `connector_sync_log`, `connector_documents` tables
- **Added** `AdminConnectorController` — full CRUD + manual sync + credential test + sync log endpoints
- All connector credentials encrypted with AES-256-GCM before storage

#### Pillar 2 — SSO (Google OAuth 2.0 + OIDC + SAML 2.0)
- **Added** `SsoService` — Google OAuth 2.0 (code exchange + ID token parsing), generic OIDC (discovery document + token endpoint), lightweight SAML 2.0 (XML assertion parsing)
- **Added** `SsoController` — authorize, callback, SAML ACS, admin CRUD for SSO providers
- **Added** `V4__sso.sql` — `sso_providers`, `oauth_state` tables
- Auto-provisioning: first-time SSO users created with role `user`, matched by email on subsequent logins
- State nonce stored in DB for CSRF protection on OAuth callbacks
- SSO client secrets encrypted with AES-256-GCM

#### Pillar 3 — Web Search (Serper / Brave / SearXNG)
- **Added** `WebSearchService` — Serper (Google results), Brave Search, SearXNG self-hosted; graceful empty-list fallback on missing config
- **Added** `WebSearchService.SearchResult` record
- **Integrated** into `ChatController` — `enableWebSearch: true` injects up to 5 results into system prompt with `search_status` SSE event
- **Added** `V5__search_images.sql` — settings columns for `searchProvider`, `searchApiKey`, `searchBaseUrl`

#### Pillar 4 — Voice + Image Generation
- **Added** `VoiceService` — OpenAI Whisper STT (hand-built multipart/form-data body, 60 s timeout); OpenAI TTS-1 (returns raw MP3 bytes)
- **Added** `VoiceController` — `POST /api/voice/transcribe`, `POST /api/voice/speak`
- **Added** `ImageGenerationService` — DALL-E 3 (CDN URL response) + Stable Diffusion Automatic1111 (base64 PNG response); `ImageResult` record
- **Added** `ImageController` — `POST /api/images/generate`
- Frontend: microphone button (Web Audio API / MediaRecorder), image generation button, voice playback

#### Pillar 5 — Deep Research
- **Added** `DeepResearchService` — 3-step pipeline: Plan (LLM decomposes query → 3–5 sub-questions), Search (web + RAG per sub-question), Synthesise (LLM report from all context)
- **Integrated** into `ChatController` — `deepResearch: true` activates pipeline instead of standard chat
- Streams `research_step` SSE events for plan/search/synthesise phases
- Frontend: Research toggle button in composer, progress cards in chat

#### Code Sandbox
- **Added** `CodeSandboxService` — `ProcessBuilder` isolated execution; Python, JS, Ruby, Java (compile-then-run), Bash; 10-second SIGKILL timeout; stripped environment; 4 096-char output cap
- **Added** `CodeSandboxController` — `POST /api/sandbox/run`

---

## v2026.1.2 — 2026-05-22

### ✨ Spring AI 1.0.0 — RAG, Prompt Templates, Function Calling

#### RAG / Vector Store
- **Added** `RagService` — document ingestion with paragraph-aware chunking + overlap, PDF and text extraction
- **Added** `EmbeddingService` — calls Ollama `/api/embed` for vector embeddings; cosine similarity search with keyword-match fallback when no embedding model is available
- **Added** `V2__rag.sql` Flyway migration — `rag_documents` + `rag_chunks` tables
- **Added** `DocumentController` — `POST /api/documents/upload` (PDF/TXT/MD, max 10 MB), `GET /api/documents`, `DELETE /api/documents/{id}`
- **Wired** RAG context automatically injected into every chat system prompt (top-5 relevant chunks above 0.30 threshold)

#### Prompt Templates
- **Added** `PromptTemplateService` — Spring AI `PromptTemplate`-based system prompts with `{variable}` substitution
- **Replaced** manual string concatenation in `ChatService.buildSystemPrompt()` with structured per-mode templates
- **Modes covered**: ask, build, review, fix, debug, test, docs, plan, learn — each with precise, expert-level instructions

#### Function Calling
- **Added** `FunctionCallService` — 4 callable tools the AI can invoke:
  - `get_datetime` — current date, time, day of week, timezone
  - `calculate` — safe math expression evaluator
  - `search_knowledge_base` — searches the RAG vector store on demand
  - `get_system_info` — product version and runtime info
- **Wired** tool calling into Ollama chat calls (1 round-trip maximum to prevent loops)
- **Dependency**: `spring-ai-client-chat` from Spring AI BOM 1.0.0, `pdfbox:3.0.4`

---

## v2026.1.1 — 2026-05-22

### 🔧 Dependency Upgrade

- **Upgraded** runtime to **Oracle Java 26** (JVM 26.0.1)
- **Upgraded** `sqlite-jdbc` from `3.46.1.3` → `3.49.1.0` (latest)
- **Confirmed** Spring Boot `3.5.3` is current latest stable
- **Added** `maven.compiler.release=21` — compiles to Java 21 LTS bytecode (Spring Boot ASM compatibility); runs on Java 26 JVM
- **Updated** README, VERSION.md, ARCHITECTURE.md to reflect Java 26 runtime

---

## v2026.1.0 — 2026-05-22

### 🚀 Major: Complete Backend Migration to Java Spring Boot

The entire backend has been rewritten from Node.js/Express to **Java Spring Boot 3.3.5**. All API endpoints, business logic, and frontend behavior are preserved exactly. No Docker required.

#### Backend (Breaking Change — Node.js removed)
- **Replaced** Node.js/Express with Spring Boot 3.3.5 (embedded Tomcat)
- **Replaced** `better-sqlite3` with SQLite JDBC + Spring `JdbcTemplate`
- **Replaced** `node-pty` terminal with `ProcessBuilder` WebSocket terminal
- **Replaced** Docker-only runtime enforcement with standalone JAR execution
- **Added** Flyway database migrations (`V1__init.sql`) — schema applied automatically on startup
- **Added** HikariCP connection pool (pool-size=1 for SQLite single-writer constraint)
- **Removed** `Dockerfile`, `docker-compose.yml`, `.dockerignore` — Docker completely removed
- **Removed** `package.json`, `package-lock.json`, `server.js`, entire `src/` Node.js tree
- **Removed** `infra/` folder (Postgres init SQL no longer needed)

#### New Java Source Structure
- 40 Java source files across controllers, services, filters, config, models, util
- `CryptoService` — AES-256-GCM (compatible with existing encrypted keys in DB)
- `RouterService` — full Auto Router scoring logic ported from Node.js
- `ProviderService` — all AI provider integrations (Ollama, Anthropic, OpenAI, Groq, custom)
- `BackupService` — `@Scheduled` daily SQLite backup with 7-file rotation
- `OllamaService` — `@Scheduled` 60-second model sync
- `AuthService` — in-memory session map + DB persistence

#### Security Fixes (QA Audit)
- **CRIT-1** Added `WebSocketAuthInterceptor` — terminal WebSocket now requires authenticated session + `workspace:build` right
- **CRIT-2** Session cookie now supports `Secure` flag via `COOKIE_SECURE=true` env var
- **CRIT-3** Login rate limit no longer bypassable via spoofed `X-Forwarded-For` headers
- **CRIT-4** Fixed XSS — feedback button `onclick` replaced with `data-*` attributes + delegated event listeners
- **CRIT-5** Fixed DOMPurify misconfiguration — removed `onclick` from `ADD_ATTR` in AI response rendering
- **CRIT-6** `/api/bootstrap` no longer returns admin email to unauthenticated requests
- **CRIT-7** Default admin password auto-generated on first boot via `SecureRandom` — printed once to server log
- **HIGH-2** Login now enforces `access_expires_at` — expired accounts cannot log in
- **HIGH-3** Added `UrlValidator` — SSRF protection blocks private/loopback IPs on all provider URL inputs
- **HIGH-4** Workspace file browser restricted to `user.home` and `data/` — no full filesystem access
- **HIGH-5** Workspace root setting validated — cannot be set outside safe paths
- **HIGH-7** Admin user updates now call `invalidateUserSessions()` — stale sessions cleared immediately
- **HIGH-8** Removed `activeUserId` global setting race condition
- **MED-1** Added `Content-Security-Policy` header to all responses
- **MED-2** Added `Strict-Transport-Security` (HSTS) header
- **MED-9** Feedback endpoint now validates message ownership before accepting rating
- **LOW-1** Workspace browse requires `workspace:build` right
- **LOW-7** Admin accounts cannot delete themselves
- **LOW-8** Audit events only returned to admin-role users in `/api/state`

#### UI Fixes
- Fixed bottom layout clipping — `app-sidebar`, `app-main`, `app-right-panel` changed from `height:100vh` to `height:100%` (columns were overflowing their grid container by 28px)
- Fixed `write-toggle` label wrapping in composer footer (`flex-wrap:nowrap` + `margin-left:auto`)
- Removed third-party brand reference from CSS comment
- Removed reference mockup image from repository
- Added Eclipse IDE files (`.classpath`, `.project`, `.settings/`) to `.gitignore`

#### Frontend (unchanged)
- All files in `public/` are identical to v2026.0.30 except the two XSS security fixes above

---

## v2026.0.30 — 2026-05-21

### UI Redesign — 3-Column Workspace Layout
- Complete redesign of user-facing UI (`app.html`, `styles.css`) to a modern 3-column layout
- Left sidebar: brand, New Chat button, chat history, agent shortcuts, user profile
- Centre: chat header with model pill, message stream, composer
- Right panel: context sources, workspace info, router card
- Floating card design with rounded corners and shadow on warm parchment background
- Sidebar models list removed — model selection moved to composer dropdown only
- Admin pages completely unchanged

### Bug Fixes
- Fixed new chat creating a duplicate session instead of archiving the current one — `POST /api/chat/clear` now force-archives regardless of message count
- Removed `+` New Chat icon button from chat header (redundant with sidebar button)
- Removed Session card from right context panel

---

## v2026.0.29 — 2026-05-21

### Admin Reports — KPI Card Height Fix
- Fixed KPI cards in the Reports tab having inconsistent heights when values were long (e.g. `31815ms`)
- Added `min-height: 110px`, `justify-content: space-between`, `word-break: break-all` to `.rpt-kpi-card`

---

## v2026.0.28 — 2026-05-21

### Streaming UX — Phase Indicators
- Real-time SSE streaming with `routing → streaming → done` phase transitions
- Routing phase shows which model was selected and why before tokens arrive
- Failed model calls show informative fallback message with route reason

---

## v2026.0.27 and earlier

See git history for earlier changes.
