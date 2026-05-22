# Changelog

All notable changes to Olla Nest are documented here.

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
