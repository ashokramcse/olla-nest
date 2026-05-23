# Changelog

All notable changes to Olla Nest are documented here.

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
