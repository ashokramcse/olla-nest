# Olla Nest — Complete Feature Reference

> Exhaustive, segment-by-segment catalogue of every feature in the platform — UI,
> API, backend services, data model, integrations, configuration, and operations.
> Organized as **Segment → Sub-segment → Feature**. Endpoint paths and env vars are
> included so nothing is left undocumented.

**Conventions**
- `MODULE` tags: **[A]** = admin service (8080), **[U]** = user service (8081), **[C]** = shared common module.
- API base paths are shown per controller; full path = base + sub-path.
- "Endpoint" lists are the concrete HTTP routes that back each feature.

---

## 1. Platform & Architecture

### 1.1 Services / Topology
- **Admin Control Panel** — `olla-nest-admin`, default port **8080** (`ADMIN_PORT`), public base URL `ADMIN_BASE_URL`.
- **Employee Workspace** — `olla-nest-user`, default port **8081** (`USER_PORT`), public base URL `BASE_URL`.
- **Shared core** — `olla-nest-common` (services, model, security, persistence) packaged into both apps.
- **Standalone logging stack** — Grafana (default **8082**, `GRAFANA_PORT`) + Loki (default **3100**, `LOKI_PORT`), run via `scripts/start_monitoring.sh`.
- **Local Whisper STT server** — auto-started Python `faster-whisper` HTTP server on port **8765** (`WhisperServerManager`).

### 1.2 Runtime / Build
- Java 26 runtime, Maven multi-module build (`mvn clean package`), Spring Boot embedded Tomcat.
- Native access flag (`--enable-native-access=ALL-UNNAMED`) for SQLite JDBC.
- Fully configurable ports/base URLs/cookie names per service (any port supported).

### 1.3 Persistence
- **SQLite** database (WAL mode) shared by both services (`spring.datasource.url`, `DATA_DIR`).
- **Flyway** migrations V1–V12, owned by the admin service (auto-applied on startup, baseline-on-migrate).
- HikariCP pool (size 1, busy-timeout, foreign keys + synchronous PRAGMAs).
- Static frontend served from `STATIC_DIR` (default `./public`).

### 1.4 PWA / Static frontend
- Single-page apps: `app.html` (workspace), `admin.html` (admin), `login.html`, `admin-login.html`.
- `manifest.json` PWA manifest, `sw.js` service worker (network-first offline shell), `robots.txt`, favicon/logo SVG.
- Shared assets: `styles.css`, `theme.js` (theming), `dropdown.js`, `features.js`.
- Cache-busting versioned script includes (`admin.js?v=…`).

---

## 2. Authentication & Session Management  **[C]**

### 2.1 Local login  — `AuthController` `/api/auth`
- `POST /login` — email + password sign-in; returns user object + `redirectTo`.
- `POST /logout` — invalidates session cookie (CSRF header required).
- `GET /me` — authenticated status + current user (unauthenticated-safe).

### 2.2 Session tokens & cookies  — `AuthService`
- 256-bit `SecureRandom` hex session tokens (64-char), validated by regex.
- 12-hour session lifetime; in-memory session cache (cap 10,000) + SQLite-persisted sessions.
- HttpOnly, `SameSite=Lax`, `Path=/` cookies; optional `Secure` flag (`COOKIE_SECURE`).
- **Per-service cookie name** (`SESSION_COOKIE_NAME`) — admin `olla_nest_session`, user `olla_nest_user_session` — so both apps stay independently logged in on the same host.
- Session invalidation cascade: role/permission changes immediately expire user sessions.

### 2.3 Brute-force protection  — `RateLimiterService`, `login_attempts`
- IP-based lockout (10 attempts / 15 min), DB-persisted (survives restart).
- Constant-time-ish login responses (BCrypt work factor) to resist user enumeration.
- Trusted-proxy aware client IP resolution (`X-Forwarded-For` / `X-Real-IP`, `TRUSTED_PROXY`).

### 2.4 First-boot bootstrap  — `BootstrapController` **[U]**, seed logic
- Seeds default admin on first start (`DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD`).
- Auto-generates + prints a random admin password once if none set.
- Default password for admin-created user accounts (`DEFAULT_USER_PASSWORD`).

### 2.5 SSO / Enterprise auth  — `SsoController` `/api/auth/sso`, `SsoService`
- Protocols: **OAuth2 / OIDC** and **SAML**.
- `GET /providers` — list enabled SSO providers (login page).
- `GET /authorize/{providerId}` — begin OAuth/OIDC flow (with `oauth_state`).
- `GET /callback` — OAuth/OIDC code exchange → session → `/app`.
- `POST /saml/acs` — SAML assertion consumer service → session → `/app`.
- Admin provider CRUD: `GET/POST /admin/providers`, `PATCH /admin/providers/{id}`, `DELETE /admin/providers/{id}`.
- Encrypted client secrets (`sso_providers.client_secret_enc`).

### 2.6 API tokens (programmatic auth)  — `ApiTokenController` `/api/tokens`, `ApiTokenService`
- Token minting with prefix + BCrypt-hashed secret, scopes (`scopes_json`).
- `DELETE /{id}` — revoke a token.
- Token validation never exposes the hash; `is_active` toggle.

### 2.7 Companion device pairing  — `CompanionController` `/api/companion`
- `GET /info` — companion config; `POST /pair` — pair a device (`companion_tokens`); `GET /ping` — liveness.

---

## 3. Access Control, RBAC & Governance  **[A]/[C]**

### 3.1 Roles & permissions
- Role catalog (`role_catalog`) and permission catalog (`permission_catalog`).
- Rights examples: `admin:manage`, `users:manage`, `models:manage`, `chat:use`, `workspace:build`.
- Per-user effective access computation; per-user overrides (`user_overrides`).

### 3.2 Organizational structure
- **Departments** (`departments`) with department-level rights.
- **Groups** (`groups`, `user_groups`) and **Teams** (`teams`).
- Access grants (`access_grants`) linking principals to resources.

### 3.3 User management  — `AdminUserController` `/api/admin`
- `GET /users`, `POST /users`, `GET /users/{id}`, `PATCH /users/{id}`, `DELETE /users/{id}`.
- `POST /users/{id}/reset-password` — admin password reset.
- `GET /users/{id}/effective-access` — resolved permissions view.
- `POST /users/{id}/overrides`, `GET /overrides`, `PATCH /overrides`, `DELETE /overrides/{id}` — per-user permission overrides.
- Active sessions: `GET /sessions/active`; force-logout: `DELETE /sessions/user/{userId}`.
- Per-user AI quotas/limits: daily/monthly token limits, GPU quota minutes, VRAM limit, concurrent-model limit, API rate limit, max context size, AI access tier, access status/expiry, security risk score, MFA flag.

### 3.4 Teams admin  — `AdminTeamsController` `/api/admin/teams`
- `PATCH /{id}`, `DELETE /{id}` — manage teams.

### 3.5 Settings & department rights  — `AdminSettingsController` `/api/admin`
- `GET/POST /settings` — global settings store (`settings`).
- `GET /departments`, `PATCH /departments/{id}/rights` — department rights matrix.
- `POST /settings/backup` — trigger a backup.

### 3.6 Model governance  — `AdminModelsController` `/api/admin`
- `PATCH /models/{id}/governance` — governance tier, resource tier, sensitivity, GPU requirement, concurrency, privacy.
- `GET /ollama/ping` — Ollama connectivity from admin.

---

## 4. Admin Control Panel (UI tabs)  **[A]**

> Served by `AdminPageController` (`/admin`, `/admin-login`, legacy `/login` → `/admin-login` redirect). Front-end `admin.html` + `admin.js`.

### 4.1 Overview — dashboard
- Counts: models, users, departments, groups; Ollama connection + model count.
- Governance summary: roles, permissions, governed models, active sources.

### 4.2 Models
- Model catalog management, governance editing, provider model sync.

### 4.3 Users — see §3.3.

### 4.4 Access Control — roles, permissions, overrides (see §3).

### 4.5 Settings — global settings, departments, backups (see §3.5).

### 4.6 Providers  — `AdminProvidersController` `/api/admin/providers`
- `PUT /{id}`, `DELETE /{id}` — provider CRUD.
- `POST /{id}/test` — credential/connection test; `POST /{id}/sync` — sync provider models.
- `GET /{id}/models`, `POST /{id}/models`, `PUT /{id}/models/{modelId}`, `DELETE /{id}/models/{modelId}` — per-provider model management.
- Encrypted API keys at rest (AES-256-GCM).

### 4.7 Connectors  — `AdminConnectorController` `/api/admin/connectors`
- `GET /types` — available connector types; `PATCH /{id}`, `DELETE /{id}`.
- `POST /{id}/sync` — manual sync; `POST /{id}/test` — connection test; `GET /{id}/logs` — sync logs.

### 4.8 SSO — provider management (see §2.5).

### 4.9 Reports  — `AdminReportsController` / `AdminEnterpriseController`
- `GET /api/admin/feedback` — user feedback report.
- Enterprise analytics: `GET /api/admin/enterprise/analytics`; audit trail `GET /api/admin/enterprise/audit`.
- Daily activity, token leaderboard, model usage, latency, department breakdown (reporting domain).

### 4.10 Enterprise / Teams memory & skills  — `AdminEnterpriseController` `/api/admin/enterprise`
- `GET/POST /teams/{teamId}/memory` — team memory; `GET /teams/{teamId}/skills` — team skills.
- `POST /connectors/{connectorId}/extract-memory` — extract memory from a connector.
- `GET /jobs` — background jobs; `POST /teams/{teamId}/onboard-user/{userId}` — onboard a user into a team.

### 4.11 MCP servers  — `AdminMcpController` `/api/admin/mcp`
- `GET/POST /servers`, `DELETE /servers/{id}` — MCP server registry.
- `POST /servers/{id}/connect` / `disconnect` — lifecycle; `POST /servers/{id}/tools`, `GET /tools` — tool discovery.

### 4.12 Skills moderation  — `AdminSkillsController` `/api/admin/skills`
- `POST /{id}/approve`, `POST /{id}/archive`, `DELETE /{id}` — curate the shared skills library.

### 4.13 Health  — `AdminHealthController`
- `GET /api/admin/health` — uptime, DB stats, JVM memory, Ollama status.

---

## 5. Employee Workspace (UI panels)  **[U]**

> Served by `UserPageController` (`/`, `/login`, `/app`). Front-end `app.html` + `app.js`.

### 5.1 Chat (core)  — `ChatController` `/api`
- `POST /chat` — send a message (streaming responses), model routing, RAG, tools, web search.
- `POST /chat/clear`, `DELETE /chat` — clear conversation.
- `POST /feedback` — thumbs/feedback on responses (`feedback`).
- Chat sessions & messages persisted (`chat_sessions`, `chat_messages`).
- Composer toggles: **Web** search augmentation; attachments; voice input; image generation.

### 5.2 Threads / sessions  — `ThreadController` `/api/threads`, `SessionEnhancementController` `/api/sessions`
- `DELETE /{id}`, `PATCH /{id}` — manage threads; `POST /{id}/activate`, `POST /{id}/fork`.
- Session enhancement: `POST /{sessionId}/fork`, `POST /{sessionId}/truncate`, `POST /{sessionId}/analyze-topics`.
- Context compaction (`ContextCompactorService`) for long sessions.

### 5.3 Personal Assistant / Crew  — `AssistantController` `/api/assistant`, `PersonalAssistantService`, `crew_members`
- `GET /check-ins` — assistant check-ins; configurable avatar, personality, greeting, enabled tools, timezone, autonomous-email permission.

### 5.4 Agent loop  — `AgentController` `/api/agent`, `AgentLoopService`
- `POST /run/{sessionId}` — run an autonomous agent loop; `POST /cancel/{sessionId}`; `GET /status/{sessionId}`.

### 5.5 Calendar  — `CalendarController` `/api/calendar`, `CalendarService`
- Calendars: `GET/POST /calendars`, `DELETE /calendars/{id}`, `GET /calendars/{id}/export.ics`.
- Events: `GET /events`, `POST /calendars/{calendarId}/events`, `PUT /events/{id}`, `DELETE /events/{id}`.

### 5.6 Contacts  — `ContactsController` `/api/contacts`, `ContactsService`
- `GET /search`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `GET /export.vcf` (vCard export).

### 5.7 Email  — `EmailController` `/api/email`, `EmailService`
- Accounts: `GET/POST /accounts`, `GET /accounts/{id}`, `DELETE /accounts/{id}` (IMAP/SMTP, encrypted passwords).
- Messages: list/read by account & thread; `POST …/read`, `POST …/star`, `DELETE …` message.
- Compose: `POST /accounts/{accountId}/send`; `POST …/reply-draft` (AI reply draft).
- AI features: summary, tags, urgency score per message.

### 5.8 Notes  — `NotesController` `/api/notes`, `NotesService`
- `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/pin`, `POST /{id}/archive`.
- Note types, color/label, pinned/archived, due date, repeat, image, sort order.

### 5.9 Tasks & scheduler  — `TasksController` `/api/tasks`, `TaskSchedulerService`
- `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/pause`, `POST /{id}/resume`, `GET /{id}/runs`.
- Schedules: daily/weekly/once; next-run computation; notifications toggle; task runs history (`task_runs`).

### 5.10 Memory  — `MemoryController` `/api/memory`, `MemoryService`, `MemoryExtractorService`
- `GET /search` — semantic memory search; `DELETE /{id}`; `POST /import`; `GET /export`.
- Automatic memory extraction from conversations.

### 5.11 Skills library  — `SkillsController` `/api/skills`, `SkillsService`
- `GET /search`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/use` (usage count).
- Fields: category, tags, platforms, when-to-use, procedure, pitfalls, verification, confidence, version, source.

### 5.12 Presets / prompt templates  — `PresetsController` `/api/presets`, `PresetService`, `PromptTemplateService`
- System presets (7 built-in) + user templates: `POST /templates`, `PUT /templates/{id}`, `DELETE /templates/{id}`.

### 5.13 Compare (model A/B)  — `CompareController` `/api/compare`, `CompareService`
- `POST /start`, `POST /{id}/vote`, `GET /history`, `GET /{id}` — side-by-side model comparison & voting.

### 5.14 Deep Research  — `ResearchController` `/api/research`, `DeepResearchService`
- `GET /tasks`, `DELETE /tasks/{id}` — multi-step research pipeline (decompose → search → synthesize), streamed; cited reports.

### 5.15 Gallery (images)  — `GalleryController` `/api/gallery`, `GalleryService`
- Albums: `GET/POST /albums`, `DELETE /albums/{id}`.
- Images: `GET /images`, `POST /upload`, `DELETE /images/{id}`.
- Drafts (editor): `GET/POST /drafts`, `GET /drafts/{id}`, `DELETE /drafts/{id}` (`editor_drafts`).

### 5.16 Cookbook (model catalog)  — `CookbookController` `/api/cookbook`, `CookbookService`
- `GET /hardware` — detected hardware; `GET /catalog` — recommended models; `GET /downloads`; `POST /download` (pull Ollama models).

### 5.17 Account / profile  — `AccountController` `/api/account`
- `GET /profile`, `PATCH /profile`, `POST /password` (change), `GET /usage` (token/quota usage).

### 5.18 Workspace files & terminal  — `WorkspaceController` `/api/workspace`, `WorkspaceService`, `TerminalService`
- `GET /browse` — browse workspace files; `POST /local-settings` — workspace prefs (`workspace_prefs`).
- Permission modes; local artifact writing; **in-browser terminal over WebSocket** (auth + `workspace:build` right).

### 5.19 Code sandbox  — `CodeSandboxController` `/api/sandbox`, `CodeSandboxService`
- `POST /run` — execute code; `GET /languages` — supported languages; permission-gated (`V8` sandbox permission).

### 5.20 Documents (RAG ingest)  — `DocumentController` `/api/documents`, `PersonalDocumentController`
- `POST /upload`, `DELETE /{id}` — upload docs for RAG; personal docs `POST /upload`, `POST /extract-text`.
- PDF parsing via Apache PDFBox.

### 5.21 Voice  — `VoiceController` `/api/voice`, `VoiceService`
- `POST /speak` — TTS; STT via local faster-whisper (WAV/MP3/WebM/OGG/M4A/FLAC) or external provider (selectable in Admin → Settings → Voice STT Provider).

### 5.22 Image generation  — `ImageController` `/api/images`, `ImageGenerationService`
- `POST /generate` — providers: OpenAI/DALL·E, Stable Diffusion / AUTOMATIC1111; logged in `image_generation_log`.

### 5.23 YouTube  — `YouTubeController` `/api/youtube`, `YouTubeService`
- `GET /transcript` — fetch & cache transcripts (`youtube_transcripts`).

### 5.24 Vault (secrets)  — `VaultController` `/api/vault`, `VaultService`
- `POST /config`, `POST /unlock`, `POST /lock`, `GET /status`, `GET /item/{name}` — encrypted personal secret store (`vault_config`).

### 5.25 Background jobs  — `BackgroundJobController` `/api/jobs`, `BackgroundJobService`
- `GET /active`, `GET /{id}`, `DELETE /{id}` — async job tracking (`background_jobs`).

### 5.26 Webhooks  — `WebhookController` `/api/webhooks`, `WebhookService`
- `GET /{id}`, `DELETE /{id}`, `POST /{id}/enable`, `POST /{id}/disable`, `POST /{id}/test` (`webhooks`).

### 5.27 Misc UI helpers
- `FontController` `GET /api/fonts/custom` — custom fonts; `EmojiController` — emoji data; `DevHintsController` `GET /api/dev/hints` — dev hints.
- `BootstrapController` — initial app bootstrap payload.

---

## 6. AI / LLM Core  **[C]**

### 6.1 Auto Router  — `RouterService`, `router_traces`
- Capability classification of request; candidate model scoring & ranking.
- `RouteResult`: selected model, tags, candidates (id/name/score/matches/breakdown), reason, privacy-blocked flag, sensitive reasons.
- Privacy/local-only enforcement; routing traces persisted.

### 6.2 Providers & models  — `ProviderService`, `ModelService`, `ModelRecord`
- Cloud providers (e.g., Anthropic, OpenAI) + local Ollama; per-model metadata (capabilities, speed/quality scores, context size, privacy, tiers, GPU, concurrency).
- `api_providers`, `api_models`, `models`, `cookbook_models`.

### 6.3 Ollama integration  — `OllamaService`, `StateController`
- `GET /api/ollama/ping`, `GET /api/ollama/models`; model pull via Cookbook.

### 6.4 Function / tool calling  — `FunctionCallService`
- Built-in tools: `get_datetime`, `calculate`, `get_system_info`, `search_knowledge_base` (+ tool-call orchestration). Requires tool-capable model.

### 6.5 RAG (retrieval-augmented generation)  — `RagService`, `EmbeddingService`
- Document chunking & embeddings (`rag_documents`, `rag_chunks`); semantic retrieval injected into chat context.

### 6.6 Web search augmentation  — `WebSearchService`, `web_search_log`
- Providers: **Tavily, Brave, Google, SearXNG, DuckDuckGo** (selectable); live-result injection.

### 6.7 Embeddings & semantic search  — `EmbeddingService`, `SearchCacheService`
- Embedding generation + cached search results (`search_cache_index`).

### 6.8 Prompt security  — `PromptSecurityService`, `PromptTemplateService`, `prompt_security_log`
- Prompt-injection / sensitive-content detection (SSN/PII patterns); template rendering; security event log.

### 6.9 Context compaction  — `ContextCompactorService`
- Summarize/trim long conversation context to fit model windows.

### 6.10 Visual reports  — `VisualReportService`
- Generate visual/structured reports from data.

---

## 7. Integrations / Connectors  **[C]**

### 7.1 Framework  — `BaseConnector`, `ConnectorRegistry`, `ConnectorSyncScheduler`
- Pluggable connector registry; scheduled background sync; encrypted credentials (`connector_configs.credentials_enc`); ingested docs (`connector_documents`); sync logs (`connector_sync_log`).

### 7.2 Available connectors (20+)
- **Dev/code:** GitHub, GitLab, Bitbucket, Jira, Linear, Asana, Confluence, Figma.
- **Docs/storage:** Google Drive, OneDrive, Dropbox, Notion, Airtable.
- **Comms:** Slack, Discord, Microsoft Teams, Gmail.
- **CRM/Support:** Salesforce, HubSpot, Zendesk.
- Each: OAuth/token credentials, content sync into RAG, per-connector test & logs.

### 7.3 MCP (Model Context Protocol)  — `McpServerService`, `mcp_servers`
- Register external MCP servers; connect/disconnect; tool discovery & invocation (admin-managed, see §4.11).

---

## 8. Notifications & Eventing  **[C]**

- **Notifications** — `NotificationService` (in-app/user notifications).
- **Event bus** — `EventBusService`, `event_log` (internal event publish/subscribe + persisted log).
- **Webhooks** — outbound webhook delivery with enable/disable/test (see §5.26).

---

## 9. Security  **[C]**

### 9.1 Cryptography  — `CryptoService`
- AES-256-GCM with random 12-byte IV per value; `ENCRYPTION_KEY` secret.
- Encrypts: provider API keys, connector credentials, SSO client secrets, email passwords, vault items.

### 9.2 HTTP security  — `SecurityConfig`, `SecurityHeadersFilter`
- CSP, HSTS (HTTPS only), `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.
- Stateless Spring Security; custom `SessionAuthFilter` for session auth.

### 9.3 Request auth & tracing  — `SessionAuthFilter`, `MdcLoggingFilter`
- Session cookie → user resolution on every request.
- MDC enrichment: `requestId`, `userId`, `userEmail`, `userRole`, `method`, `path`, `ip`.
- Per-request INFO access log line (queryable in Loki/Grafana).

### 9.4 WebSocket security  — `WebSocketConfig`, `WebSocketAuthInterceptor`
- Authenticated handshake; allowed origin from `app.base-url`; right-gated (terminal requires `workspace:build`).

### 9.5 Audit & compliance
- Audit events (`audit_events`); SOC 2-oriented logging; failed-login security events (no PII).

### 9.6 Rate limiting  — `RateLimiterService`
- Login brute-force + per-user API rate limits.

---

## 10. Observability & Operations

### 10.1 Standalone logging (Grafana + Loki)
- `scripts/start_monitoring.sh` — install/start/stop/status/restart Loki + Grafana (native, no Docker/sudo).
- Loki appender via the `loki` Spring profile (`SPRING_PROFILES_ACTIVE=loki`), endpoint `LOKI_URL`.
- Provisioned datasource + **"Olla Nest — Logs"** dashboard.
- **Separated Log Views** (per the structured fields): Admin server, Admin users, User server, All users (email-filterable).
- Dashboard filters: Service (All/Admin/User), Log Level, Search, User Email.
- Auto-enforced Grafana admin password (`scripts/monitoring/.grafana-password`).

### 10.2 Health & monitoring  — `AdminHealthController`, `MonitorService`
- `GET /api/admin/health` — uptime, DB stats, JVM memory, Ollama status.

### 10.3 Backups  — `BackupService`, `AdminSettingsController`
- `POST /api/admin/settings/backup`; backups directory under `DATA_DIR`.

### 10.4 Atomic writes  — `AtomicWriteService`
- Safe atomic file writes for workspace/artifacts.

### 10.5 Whisper STT manager  — `WhisperServerManager`
- Auto-start/monitor local Python whisper server (port 8765); one-time venv setup script.

### 10.6 Scheduled jobs
- `ConnectorSyncScheduler` (connector sync), `TaskSchedulerService` (user scheduled tasks), background job service.

---

## 11. Data Model (tables by migration)

- **V1 (core):** users, sessions, login_attempts, departments, groups, user_groups, teams, role_catalog, permission_catalog, access_grants, user_overrides, workspace_prefs, settings, models, api_providers, api_models, chat_sessions, chat_messages, router_traces, feedback, audit_events.
- **V2 (RAG):** rag_documents, rag_chunks.
- **V3 (connectors):** connector_configs, connector_documents, connector_sync_log.
- **V4 (SSO):** sso_providers, oauth_state.
- **V5 (search/images):** web_search_log, image_generation_log.
- **V6–V8:** performance indexes, cleanup/indexes, sandbox permission.
- **V9 (productivity/AI):** memories, skills, notes, scheduled_tasks, task_runs, comparisons, crew_members, user_templates, signatures.
- **V10 (PIM):** email_accounts, email_messages, email_drafts, calendars, calendar_events, contacts.
- **V11 (platform):** mcp_servers, webhooks, api_tokens, background_jobs, gallery_albums, gallery_images, editor_drafts, research_tasks.
- **V12 (extras):** cookbook_models, vault_config, companion_tokens, youtube_transcripts, search_cache_index, prompt_security_log, event_log.

---

## 12. Configuration (environment variables)

### 12.1 Required
- `ENCRYPTION_KEY` — AES-256-GCM master key (changing invalidates stored keys).

### 12.2 Ollama / models
- `OLLAMA_URL` — Ollama instance URL.

### 12.3 Ports & URLs
- `ADMIN_PORT` (8080), `USER_PORT` (8081), `ADMIN_BASE_URL`, `BASE_URL`.

### 12.4 Sessions / security
- `SESSION_COOKIE_NAME` (per service), `COOKIE_SECURE`, `TRUSTED_PROXY`.

### 12.5 Data / assets
- `DATA_DIR` (./data), `STATIC_DIR` (./public).

### 12.6 Bootstrap credentials
- `DEFAULT_ADMIN_EMAIL`, `DEFAULT_ADMIN_PASSWORD`, `DEFAULT_USER_PASSWORD`.

### 12.7 Logging / monitoring
- `GRAFANA_PORT` (8082), `LOKI_PORT` (3100), `LOKI_URL`, `LOKI_ENABLED`, `SPRING_PROFILES_ACTIVE=loki`.

### 12.8 Optional provider keys
- `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` (also settable in Admin → Providers).

---

## 13. Cross-cutting feature flags / toggles
- Per-user: notifications enabled, autonomous email, AI access tier, MFA flag, quotas.
- Per-model: governance tier, resource tier, privacy, sensitivity allowed, GPU required, concurrency, context size.
- Per-task: schedule type, notifications, status (active/paused).
- Chat composer: web search, attachments, voice, image generation.
- Theme: light/dark theming (`theme.js`), custom fonts.

---

*Generated from the codebase: 50 controllers/endpoints, 54 services, 22 connectors, 12 migrations, frontend SPA assets, security/observability stack, and configuration. Keep this in sync when adding features.*
