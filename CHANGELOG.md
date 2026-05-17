# Changelog

All notable changes to Olla Nest are documented here.  
Versioning scheme: `v{YEAR}.{MINOR}.{PATCH}` — see [VERSION.md](VERSION.md).

---

## [v2026.0.10] — 2026-05-17

### ✨ Features

- **Full external provider support**: Any configured Anthropic, OpenAI, Groq, or custom provider now works in both streaming and non-streaming chat. The old hardcoded block (`"API connector not configured in this MVP"`) is removed entirely.
- **Provider test — dynamic model selection**: The Test Connection button in Admin → Providers no longer uses hardcoded model names (`claude-3-5-haiku`, `gpt-3.5-turbo`). It picks the first real synced model from the provider's `api_models` table. If no models have been synced yet, it tells you to run Sync Models first.
- **Anthropic model sync — real API**: Syncing an Anthropic provider now calls the real Anthropic `/v1/models` endpoint and returns exactly the models your API key can access. Previously this returned a hardcoded 3-model static list.
- **External models visible to router**: When an admin approves a model from `api_models` (Admin → Providers → Approve), it is now mirrored into the main `models` table. The Auto Router and model picker immediately see it — no restart needed. Removing approval removes it from the router.
- **Context window lookup for cloud models**: The sliding-window history algorithm now checks `api_models.context_window` for external provider models. Claude gets 200k tokens, Groq models get their real limits — not a fallback 8192.

### 🛠 Internal

- `resolveProvider(db, route)` helper centralises provider resolution for both `/api/chat` and `/api/chat/stream` — removes duplicated inline provider lookup code.
- `mirrorApiModelToModels(db, provider, apiModel)` helper keeps `api_models` and `models` tables in sync when approval status changes.

---

## [v2026.0.9] — 2026-05-17

### ✨ Features

- **Model-agnostic context window**: Removed all hardcoded model name pattern matching (`/gemma4/`, `/qwen/`, etc.) from `contextWindowForModel()`. Now queries Ollama's `/api/show` endpoint for the real context length of each local model. Results are cached in `models.context_size` on every sync so lookups are instant.
- **Universal fallback**: Any model whose context window cannot be determined from Ollama or the provider falls back to 8192 tokens — a safe universal default.

---

## [v2026.0.8] — 2026-05-17

### ✨ Features

- **Chat session memory**: Every chat message now loads the full conversation history from the database and passes it to the model. Conversations are contextually aware across turns — the model can reference earlier messages exactly like Claude Chat. Uses a sliding-window algorithm: walks history newest→oldest, keeps messages that fit within the model's token budget, drops the oldest when the window is full.
- **File upload in composer**: New attachment button (📎) in the chat composer. Supports images (`jpg`, `png`, `webp`, `gif`) and text files (`txt`, `md`, `json`, `js`, `ts`, `py`, `html`, `css`, `yaml`, `csv`). Images are sent as base64 to Ollama's multimodal endpoint (works with `gemma4:26b`, `llava`, etc.). Text files are appended as code blocks in the message body. Preview chips shown before sending; up to 5 attachments; click × to remove any attachment.
- **Model picker dropdown fix**: The Claude-style model picker in the composer was clipped by `overflow:hidden` on the composer wrapper. Fixed by switching to `position:fixed` with dynamic `getBoundingClientRect()` positioning — the dropdown now renders as a proper popup, fully visible above the textarea.

### 🛠 Internal

- `buildSystemPrompt(mode, route, workspace)` — extracted from `modelPrompt()` to return only the system-level instructions (no user message). Enables clean multi-turn message arrays.
- `buildContextMessages(db, sessionId, systemPrompt, userMessage, modelName, images)` — builds the full `[system, ...history, user]` array with sliding-window budget management.
- `estimateTokens(text)` — `Math.ceil(length / 4)` token estimator for budget calculations.

---

## [v2026.0.7] — 2026-05-17

### ✨ Features

- **Auto-save governance tier**: The "Save policy" button is removed from the model governance dropdown in Admin → Models. The tier now auto-saves on dropdown change — no extra click required.
- **Claude-style model picker in workspace**: The plain `<select>` model dropdown in the employee chat composer is replaced with a Claude-style popup picker showing model name, availability dot, and context window size. The hidden `<select>` is kept for form submit compatibility.
- **LAN IP false warning removed**: The preemptive warning that fired on LAN IP addresses (even when Ollama was reachable) is removed. Instead, Olla Nest auto-tests the connection after saving and shows the real result (model count or error).
- **Ollama header updates after save**: Saving a new Ollama URL now auto-pings the new address and immediately updates the header connection dot and label — no page refresh needed.

### 🐛 Bug Fixes

- `forceLogoutUser` used `state.me?.id` — property is `state.activeUser`. Fixed to prevent admins from accidentally logging themselves out.

---

## [v2026.0.6] — 2026-05-17

### ✨ Features

- **Branded toast notifications**: `showToast(msg, type, duration)` — bottom-right slide-in toasts replacing all native `alert()` calls. Types: `success`, `error`, `warning`, `info`.
- **Branded confirm dialogs**: `showConfirm(msg, onConfirm)` — overlay confirmation dialog replacing all native `confirm()` calls. Consistent with app design system.
- **Permission groups with colour coding**: The inline employee edit panel now renders permissions in 4 colour-coded groups — Core (green), Models (blue), Workspace (amber), Admin (red) — with coloured headers and bordered sections for instant visual scanning.
- **CSP fix for admin markdown**: `cdnjs.cloudflare.com` added to `script-src` and `style-src` in the Content Security Policy. This was blocking `marked.js` and `highlight.js` in the admin chat tab, causing plain-text rendering.
- **Ollama sync diagnosis**: Admin sync button now pings Ollama first with a 10-second timeout. Shows model count on success, shows the exact URL and error on failure. Never hangs indefinitely.

### 🐛 Bug Fixes

- Fixed `syncOllamaModels()` returning a plain array — now returns `{ ok, models, error }` object so callers can distinguish success from failure.
- Fixed "Ollama connected" showing falsely when Ollama was unreachable — `checkOllama()` now uses the `/api/admin/ollama/ping` diagnostic endpoint.

### 🗑️ Removed

- **Apply Override** card removed from Access Control tab — was confusing and not needed.
- **Active Sessions** card removed — expanded User Access card to full width.
- **Security Policies** card removed — expanded Department Defaults card to full width.

---

## [v2026.0.5] — 2026-05-17

### 🐛 Bug Fixes

- **Docker cache not showing changes**: Added `?v=2026.0.x` version query strings to all `<script>` tags. Reduced JS/CSS `Cache-Control` from `max-age=86400` (1 day) to `max-age=300` (5 minutes) so rebuilds are always reflected in the browser.

---

## [v2026.0.4] — 2026-05-17

### ✨ Features

- **Inline employee editor**: Each user row in Admin → Users now has an Edit button. Clicking it expands an inline panel with all editable fields (name, email, role, department, token limits, API rate limit), a full permission checkbox grid, a Change Password button, and a Deactivate / Reactivate button — no page navigation required.
- **Human-readable permissions**: All permission keys (e.g. `models:local:use`) are replaced with clear labels (e.g. **Local AI Models**) throughout the admin UI. Every badge and checkbox includes a tooltip with a plain-English description.
- **Permission risk indicators**: High-risk permissions (Terminal & Workspace, Admin Panel) are visually highlighted with a red border on their checkbox cards.

### 🐛 Bug Fixes

- Edit panel save PATCHes all fields (name, email, department, role, token limits, rights array) in a single request and re-renders the user list.

---

## [v2026.0.3] — 2026-05-17

### 🔒 Security

- **HSTS**: `Strict-Transport-Security` header added for HTTPS connections (1-year max-age, includeSubDomains).
- **XSS prevention**: AI-generated markdown now sanitised with DOMPurify before rendering — prevents prompt-injection attacks from executing scripts in the browser.
- **Session fixation fix**: Old session token explicitly invalidated on login.
- **Logout CSRF**: `POST /api/auth/logout` now requires `X-Requested-With: XMLHttpRequest` header.
- **CSP improvement**: `connect-src` explicitly allows `ws:` and `wss:` for the WebSocket terminal.
- **`workspace:build` risk level**: Updated to `critical` in the permission catalog — this grants interactive shell access inside the container.

### ⚡ Performance

- **6 database indexes added** — all were missing, causing full table scans:
  - `chat_messages(session_id)` — message load
  - `chat_messages(created_at)` — usage reports
  - `chat_sessions(user_id, is_active)` — per-user session lookup
  - `audit_events(created_at)` — audit/reports
  - `router_traces(created_at)` — router reports
  - `feedback(message_id)` — feedback lookup
- Static assets served with `Cache-Control: public, max-age=86400`.

### 🐛 Bug Fixes

- Chat rate limiting now enforced server-side on both `/api/chat` and `/api/chat/stream`.
- Model status pill: fixed wrong status check (`"approved"` → `"available"`).
- Streaming done event: sends `messageId: null` on DB-closed mid-stream — feedback buttons correctly hidden.
- `submitFeedback()` returns early if `messageId` is null.

---

## [v2026.0.2] — 2026-05-17

### ✨ Features

- **Separate login pages**: Distinct pages for admins (`/admin-login`) and employees (`/login`). Admin login rejects non-admin accounts with a clear message.
- **Model connected status pill**: Hero bar shows which model is approved and available.
- **Daily token usage pill**: Visual `10,000 / 50,000` indicator with colour-coded progress bar.
- **Integrated terminal**: xterm.js PTY terminal for `workspace:build` users; "Run in terminal" button on shell code blocks.

### 🛠 API

- `GET /api/account/usage` — returns today's and monthly token usage for the current user.

---

## [v2026.0.1.mvp] — 2026-05-17

### 🚀 First public MVP release

**Core**
- Auto Router: classifies requests and picks the best approved local model by capability, speed, quality, and privacy score
- SSE streaming chat with real-time token streaming and abort support
- Manual model override via composer dropdown
- Sensitive content detection forces local-only routing

**Admin Dashboard**
- Overview · Models · Users · Access Control · Settings · Providers · Reports · Chat tabs
- RBAC role catalog, effective access inspector, per-user permission overrides
- 10 interactive Chart.js charts, paginated token leaderboard

**Employee Workspace**
- Chat history with session management (pin, archive, fork, rename)
- Workspace integration: generated files saved to local folder
- Profile drawer with enterprise field-locking

**Security**
- `HttpOnly` session cookies, bcrypt passwords, AES-256-GCM API key encryption
- Login rate limiting, CSRF protection, HTTP security headers, DOMPurify XSS sanitisation
- Workspace path traversal protection

**Database**
- SQLite with WAL mode
- 18 tables: `users`, `departments`, `groups`, `teams`, `models`, `access_grants`, `role_catalog`, `permission_catalog`, `user_overrides`, `chat_sessions`, `chat_messages`, `audit_events`, `router_traces`, `workspace_prefs`, `api_providers`, `api_models`, `feedback`, `settings`
- Auto migrations via `seedSql()` — safe to upgrade without data loss

---

## [Unreleased]

_Future changes will be listed here before each release._

---

*See [VERSION.md](VERSION.md) for a commit-by-commit version tracker.*
