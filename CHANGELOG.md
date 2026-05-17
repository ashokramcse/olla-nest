# Changelog

All notable changes to Olla Nest are documented here.  
Format: [Semantic-ish versioning](https://semver.org) with release dates.

---

## [v2026.0.4] — 2026-05-17

### ✨ Features

- **Inline employee editor**: Each user row in the Admin → Users tab now has an Edit button. Clicking it expands an inline panel below the row containing all editable fields (name, email, role, department, token limits, API rate limit), a full permission checkbox grid, a Change Password button, and a Deactivate/Reactivate button — no page navigation required.
- **Human-readable permissions**: All permission keys (e.g. `models:local:use`) are now replaced with clear labels (e.g. **Local AI Models**) throughout the admin UI. Every badge and checkbox includes a native tooltip (hover) with a plain-English description of what the permission actually grants.
- **Permission risk indicators**: High-risk permissions (Terminal & Workspace, Admin Panel) are visually highlighted with a red border on their checkbox cards so admins can spot them at a glance.

### 🐛 Bug Fixes

- **Edit panel save**: Saving an employee now PATCHes all fields (name, email, department, role, token limits, rights array) in a single request and re-renders the user list

---

## [v2026.0.3] — 2026-05-17

### 🔒 Security

- **HSTS**: `Strict-Transport-Security` header now sent on HTTPS connections (1-year max-age, includeSubDomains)
- **XSS prevention**: AI-generated markdown output now sanitized with DOMPurify before rendering — prevents prompt-injection attacks from executing scripts in the browser
- **Session fixation fix**: Old session token is now explicitly invalidated when a user logs in — prevents parallel session reuse
- **Logout CSRF**: `POST /api/auth/logout` now requires `X-Requested-With: XMLHttpRequest` header — prevents cross-origin logout attacks
- **CSP improvement**: `connect-src` now explicitly allows `ws:` and `wss:` for the WebSocket terminal (was implicitly blocked on some browsers)
- **workspace:build risk level**: Updated to `critical` in the permission catalog — this permission grants interactive terminal shell access inside the container

### ⚡ Performance

- **6 database indexes added** — all were missing, causing full table scans on every chat and report load:
  - `chat_messages(session_id)` — every message load
  - `chat_messages(created_at)` — token usage queries and reports
  - `chat_sessions(user_id, is_active)` — per-user chat session lookup
  - `audit_events(created_at)` — all report and audit queries
  - `router_traces(created_at)` — router report queries
  - `feedback(message_id)` — feedback lookup
- **Static asset caching**: JS, CSS, fonts now served with `Cache-Control: public, max-age=86400`; HTML pages served with `no-cache`

### 🐛 Bug Fixes

- **Chat rate limiting now enforced**: `api_rate_limit_per_minute` per user was stored in the database but never checked server-side — now enforced on both `/api/chat` and `/api/chat/stream` with a sliding-window in-memory limiter
- **Model status pill**: Fixed wrong status check (`"approved"` → `"available"`) — Ollama models use `"available"` as their active status
- **Streaming done event**: When the database is closed mid-stream (client disconnect), the done event now sends `messageId: null` instead of a non-existent ID — feedback buttons are correctly hidden when no message was persisted
- **Feedback submission guard**: `submitFeedback()` now returns early if `messageId` is null — prevents silent 404 API calls

---

## [v2026.0.2] — 2026-05-17

### ✨ Features

- **Separate login UX**: Distinct pages for admins (`/admin-login` — dark, professional) and employees (`/login` — warm, friendly). Admin login rejects non-admin accounts with a clear message.
- **Model connected status**: Hero bar now shows which Ollama model is currently approved and connected.
- **Daily token usage pill**: Visual usage indicator (`10,000 / 50,000`) with colour-coded progress bar (yellow → amber → red) driven by the new `/api/account/usage` endpoint.
- **Integrated terminal**: xterm.js PTY terminal panel for workspace:build users; "Run in terminal" button on shell code blocks; backtick keyboard shortcut to toggle.
- **Admin-only terminal access**: WebSocket terminal gated by `workspace:build` permission or `admin` role.

### 🔒 Security

- `/admin` now redirects unauthenticated visitors to `/admin-login` instead of `/login`.
- `adminOnly: true` flag sent from admin login page; non-admin users rejected at the browser before session creation.

### 🛠 API

- `GET /api/account/usage` — returns `tokensUsedToday`, `dailyTokenLimit`, `tokensUsedMonth`, `monthlyTokenLimit` for the logged-in user.

---

## [v2026.0.1.mvp] — 2026-05-17

### 🚀 First public MVP release

This is the inaugural release of Olla Nest — a company-ready local AI workspace built on top of Ollama with admin controls, automatic model routing, and enterprise-grade governance.

### ✨ Features

**Core**
- Auto Router: classifies every request and picks the best approved local model by capability, speed, quality, and privacy score
- SSE streaming chat with real-time token streaming and abort support
- Manual model override via composer dropdown
- Sensitive content detection (SSN, credit card, PHI, API keys) forces local-only routing

**Admin Dashboard**
- Overview tab: live metrics (models, users, groups, departments), audit feed
- Models tab: Ollama model sync with speed/quality scores, governance tier tagging
- Users tab: create/edit employees with enterprise profile fields (employee ID, designation, team, branch, manager, department, AI access tier, token limits)
- Access Control tab: RBAC role catalog, effective access inspector, per-user permission overrides with expiry
- Settings tab: Auto Router toggle, local write config, workspace root, API model access
- Providers tab: Ollama status + model pills, API provider configuration (OpenAI, Anthropic, Groq, custom)
- Reports tab: 10 interactive Chart.js charts (daily activity, model usage, token leaderboard, mode breakdown, dept usage, tier distribution, live vs failed, audit timeline, latency, model efficiency)
- Chat tab: admin test chat with full router panel

**Employee Workspace**
- Chat history with session management (pin, archive, fork, rename)
- Workspace integration: generated files saved to configured local folder
- Profile drawer: edit name, phone, designation, team, branch; change password; enterprise field-locking for SSO accounts
- Auto Router panel shows which model was selected and why

**Teams**
- Teams table with create/delete
- Team dropdown in user creation with inline team creation modal

**Invitations**
- Credentials (email + default password) displayed on employee creation with copy-to-clipboard
- Enterprise note on invite modal

**Reporting**
- Token leaderboard paginated (10 per page)
- `tokens_used` and `latency_ms` tracked per assistant message

### 🔒 Security

- Session cookies: `HttpOnly`, `SameSite=Lax`, `Secure` when behind HTTPS
- Login rate limiting: 10 failed attempts per IP per 15-minute window
- CSRF protection via `X-Requested-With` header check on all state-changing endpoints
- HTTP security headers: `X-Frame-Options`, `X-Content-Type-Options`, `X-XSS-Protection`, `Referrer-Policy`, `CSP`, `Permissions-Policy`
- Chat message length limit: 16,000 characters
- Express JSON body limit: 512 KB
- Password hashing: bcrypt cost factor 12
- API key encryption: AES-256-GCM
- Workspace path traversal protection
- Filesystem browse restricted to admin role
- Admin email removed from unauthenticated `/api/bootstrap` response

### 🐛 Bug Fixes

- Fixed SSE streaming crash ("database is not open") — switched `req.on('close')` to `res.on('close')` for correct abort signal timing
- Fixed admin UI infinite recursion crash on page load — removed hoisted function wrapper pattern
- Fixed models showing as "available" when Ollama is offline — now marks all ollama models as `missing` on connection failure
- Fixed Access Control cards overlapping — corrected grid layout for natural equal height
- Removed mode bar (Ask/Build/Fix/Debug/…) from chat — simplified to single chat UX; router handles task detection

### 🗄️ Database

- SQLite with WAL mode
- Tables: `users`, `departments`, `groups`, `teams`, `models`, `access_grants`, `role_catalog`, `permission_catalog`, `user_overrides`, `chat_sessions`, `chat_messages`, `audit_events`, `router_traces`, `workspace_prefs`, `api_providers`, `api_models`, `feedback`, `settings`
- Auto migrations via `seedSql()` — safe to upgrade without data loss
- Migration from legacy `documents.json` to SQLite on startup

---

## [Unreleased]

_Future changes will be listed here before each release._

---

*See [VERSION.md](VERSION.md) for a detailed commit-by-commit version tracker.*
