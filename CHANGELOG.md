# Changelog

All notable changes to Olla Nest are documented here.  
Versioning scheme: `v{YEAR}.{MINOR}.{PATCH}` — see [VERSION.md](VERSION.md).

---

## [v2026.0.30] — 2026-05-21

### ✨ Features

- **Real-time SSE streaming chat**: Admin chat (and employee workspace) switched from blocking `/api/chat` to the streaming `/api/chat/stream` endpoint. Tokens appear as the model writes them — no waiting for the full response.
- **Four-phase streaming UX**: Every response goes through visible phases — Routing (grey badge, animated dots), Thinking (blue badge, model name shown), Writing (green badge, tokens live with blinking cursor), Done (full markdown rendered, elapsed time + file chips in footer). Error state shown inline with red badge.
- **Streaming infrastructure**: `ReadableStream` reader with `TextDecoder` parses SSE line-by-line. Each `token` event appends to accumulated text and re-renders markdown in place. Blinking cursor removed on `done` event.
- **Response footer**: On completion, each response shows elapsed time chip and download chips (↓) for extracted files, plus ✓ saved chips for server-written workspace files.
- **Avatar animation**: AI avatar pulses with `stream-pulse` animation while the model is generating; settles on completion showing the model's initial letter.

### 🔒 Security

- Removed `Co-Authored-By` AI attribution lines from all git commits — git `commit-msg` hook installed globally (`~/.git-hooks-global/`) to strip them automatically from every future commit on this machine and all future projects.

---

## [v2026.0.29] — 2026-05-21

### 🐛 Bug Fixes

- **Critical crash**: `ReferenceError: c is not defined` at `admin.js:716` inside `showToast`. The `c.icon` reference was a stale leftover from before `accentColors` was refactored to a plain hex map. Replaced with the already-correct `icon` variable defined in the same function.

---

## [v2026.0.28] — 2026-05-21

### 🐛 Bug Fixes

- **Critical**: `.login-brand-sub { display:none }` in `styles.css` was hiding real content on the login and admin-login pages. Removed.
- **Empty state icon**: "Ready when you are" ✦ icon was using `var(--ac-dark)` in a gradient — rendered as dark olive in day mode. Fixed to use solid `var(--ac)` with `var(--ac-text)` foreground and a `var(--ac-pale)` ring. Icon now automatically follows the user's chosen accent colour.

### 🧹 Code Cleanup

- Removed ~280 lines of dead CSS from `styles.css`: hero section, admin sidebar layout, infographic ring/gauge/sparkline/activity CSS, `.account-panel` container, `.settings-grid/.settings-card`, `.welcome-bar/.stats-bar`, `.theme-controls` block, `.logo-mark`, `.app-shell`, `.user-avatar-wrap`, `.model-select`
- Removed responsive media query rules that referenced the deleted dead classes
- Removed duplicate `.rpt-kpi-card` definition and dead chart-row CSS from `admin.html` inline styles
- Consolidated `.page` width — removed the dead `width: 1440px` first rule (the `max-width` rule is the only rule needed)
- Refactored `.nav-item` base rule — removed sidebar-specific `border-left` so the admin tab-bar no longer needs `!important` overrides

### ✨ Polish

- Hardcoded colours replaced: `.app-model-dot` `#4caf50` → `var(--success)`, `.status-dot.off` `#aaa` → `var(--muted2)`
- Font size audit: `8px`/`9px` labels upgraded to `10px` minimum (`.topnav-stat-label`, `.logo-sub`)
- `logo-readme.svg` content now centered within the SVG viewport (44px left translate)

---

## [v2026.0.27] — 2026-05-20

### 🐛 Bug Fixes

- Architecture SVG boxes no longer overlap — widened 760→820px, all three panels (LOCAL/CLOUD/ADMIN) have 14px gaps
- Header overlap fixed: `overflow:hidden` on `.shell` changed to `overflow:clip` (was breaking sticky topnav); `padding-top:20px` added to `.main-grid` and all `.tab-view.active` panels
- Profile card in day mode was rendering dark olive (HSL 49° 96% 20%) — replaced gradient with `var(--ac-pale)` to `var(--bubble)`
- Chat height changed from fixed `680px` to `calc(100vh - 200px)` with `min-height:420px`
- Router card `top` updated to `calc(56px + 20px)` to clear the fixed header

### ✨ Features

- Reports tab fully rebuilt: 9 Chart.js canvases replaced with 10 `<table class="data-table">` elements — stable, theme-aware, no external charting dependency
- `fillTable(tblId, rows, cols, emptyMsg)` helper added to `admin.js`
- README logo enlarged to `width="480"` for high-DPI display

---

## [v2026.0.26] — 2026-05-20

### ✨ Features

- **Per-user theme storage**: accent colour and light/dark/system mode stored per user in `localStorage` keyed by `themeHex_u_${userId}` / `themeMode_u_${userId}`
- **System mode**: new `system` option in theme mode toggle — follows OS `prefers-color-scheme` automatically
- **Theme controls moved**: removed from persistent topbar into profile drawer Settings section; topbar is cleaner
- **Login redesign**: full-page 50/50 split layout — dark brand panel on left with feature list, floating card on right
- **Olla Nest logo**: custom SVG mark added with CSS-var-driven colours

---

## [v2026.0.25] — 2026-05-20

### 🐛 Bug Fixes

- Night mode surface hierarchy corrected — `bg → shell → card` layering was inconsistent
- All Chart.js charts re-read CSS variables at render time via `getComputedStyle(document.documentElement)`

### 🧹 Cleanup

- Full theme audit: every hardcoded hex colour in HTML/JS/CSS replaced with semantic CSS variables

---

## [v2026.0.24] — 2026-05-20

### 🏗 Architecture

- `theme.js` introduced: `applyTheme(hex, mode)` writes ~25 design tokens to `:root`
- All `--yellow-*`, `--black`, `--card`, `--ink-*` legacy tokens removed and replaced
- Admin tab-bar restored after design system refactor broke it
- 12-phase UX audit fixes applied: viewport meta, outline focus states, responsive breakpoints

---

## [v2026.0.23] — 2026-05-20

### ✨ Features

- `mac-home` volume renamed to `host-home` for cross-platform clarity
- In-app folder browser added to workspace config panel
- OS detection for path hints in workspace UI

### 🐛 Bug Fixes

- `showConfirm` missing from `app.js` caused crash blocking New Chat and all event listeners

---

## [v2026.0.22] — 2026-05-19

### ✨ Features

- **Thinking indicator**: Before the first token arrives, the assistant bubble shows animated pulsing dots with phase labels — "Routing…" while the Auto Router picks a model, then "Thinking…" while the model generates. Instantly replaced by streamed content on the first token.
- **Code review modal**: Every code block has a ⛶ View button. Clicking it opens a full-screen dark overlay with complete syntax highlighting, language badge, filename, and line count. Plain-text copy from within the modal. Backdrop click or ✕ to close.
- **Project Knowledge context injection**: Admin can enter project-level context (tech stack, conventions, coding standards) in **Admin → Settings → Project Knowledge**. This text is injected into every chat system prompt across all users and all modes — no per-session setup required.
- **Input history (↑/↓)**: The chat input now behaves like a terminal. ↑ navigates to older sent messages; ↓ navigates forward. Current draft is saved and restored when returning to the bottom of history. Consecutive identical messages are deduplicated.
- **SVG architecture diagram**: README now shows a polished dark-theme SVG diagram instead of ASCII art — gradients, drop shadows, coloured accent bars, proper arrowheads.

### 🐛 Bug Fixes

- **Settings route was silently broken**: `POST /api/admin/settings` was mounted at `/api/admin` with `router.post("/")`, meaning it only matched the bare `/api/admin` path. The frontend calls `/api/admin/settings`, which was falling through to the 404 catch-all and redirecting to `/login`. Fixed by changing handler to `router.post("/settings")`.
- **SQLite WAL mode broken on Docker/macOS**: WAL journal mode produces 0-byte WAL files on Docker-for-Mac virtualized filesystem — writes committed in one connection were invisible to all others. Switched to `PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL`. This fixed the core login bug where `curl` worked but browser login always failed.
- **Cluster worker contention**: Multiple cluster workers on a Docker volume caused intermittent `SQLITE_IOERR: disk I/O error` on concurrent writes. Reduced to 1 worker.

---

## [v2026.0.21] — 2026-05-19

### ✨ Features

- **Syntax highlighting**: `highlight.js` loaded for 30+ languages — JavaScript, TypeScript, Python, Rust, Go, SQL, HTML, CSS, YAML, Dockerfile, GraphQL, and more.
- **Language badges**: Colour-coded pill on every code block header — JS=yellow, TS=blue, Python=green, SQL=orange, Rust=salmon, Go=cyan, Shell=green. Colours match GitHub's language palette.
- **Line numbers**: Every code block now has a fixed-width line number gutter using a table-cell layout. Non-selectable — copying text does not include line numbers.
- **Diff view**: Lines starting with `+` render green, `-` render red, `@@` lines render as blue hunk headers. Automatically detected — no explicit `diff` language tag required if the content looks like a diff.
- **Filename header detection**: A first-line comment in the form `// filename: src/routes/auth.js` (or `# filename:`, `<!-- filename:`) is extracted and shown as a file chip in the code header bar.
- **Redesigned code header bar**: Dark bar across the top of every code block: language badge + filename on the left, ⛶ View + Copy + Run buttons on the right. No more absolute-positioned buttons overlapping code.
- **Improved inline code styling**: Inline `code` now has a distinct reddish tint to visually separate it from prose.

---

## [v2026.0.17] — 2026-05-17

### 🏗 Architecture

- **Modular refactor**: Split the monolithic 3785-line `server.js` into 31 focused modules under `src/` — `config`, `db/`, `middleware/`, `services/`, `models/`, `routes/`, `routes/admin/`. Each file has one clear responsibility. Entry point `server.js` is now 41 lines.
- **Ollama sync moved to background timer**: `syncOllamaModels()` no longer runs inside any HTTP request handler. It fires once at boot and every 30 seconds via `setInterval`, each tick with its own isolated DB connection. Eliminates the DB race condition that was preventing chats from loading.

### 🔒 Security

- **Host home volume made read-only**: `${HOME}:/mac-home` changed to `:ro` in `docker-compose.yml` — prevents write access to host filesystem from inside the container.
- **Bootstrap endpoint no longer leaks admin password**: `GET /api/bootstrap` now returns only `{ ready, adminEmail }` — never the plaintext password over HTTP.
- **PTY secrets scrubbed**: `SECRET_KEY`, `SESSION_SECRET`, `DEFAULT_ADMIN_PASSWORD`, `DEFAULT_USER_PASSWORD` are removed from the PTY environment before spawning the terminal shell.
- **Daily token quotas enforced**: Both `/api/chat` and `/api/chat/stream` now check the user's `dailyTokenLimit` before calling the model and return HTTP 429 if exceeded.
- **Sensitive content filter on manual model selection**: Running `detectSensitiveContent` is now mandatory even when the user manually picks a model — PII cannot be routed to an external provider by bypassing the Auto Router.
- **Role validated against allowlist**: `PATCH /api/admin/users/:id` now rejects any `role` value outside `["admin", "user"]`.
- **Non-root container user**: Dockerfile now creates `appuser:appgroup` and runs the process as non-root.

### ⚡ Performance

- **DDL out of `openSql()`**: All `CREATE TABLE IF NOT EXISTS` and migration checks moved to a one-time `initDatabase()` called at server boot — eliminates ~30 redundant SQLite metadata queries per request.
- **Two new indexes**: `idx_access_grants_subject(subject_type, subject_id)` and `idx_user_overrides_user(user_id)` — these columns are queried on every chat request for RBAC resolution.
- **Chat inserts wrapped in transactions**: User and assistant message inserts are atomic — no orphaned unanswered messages if the process crashes mid-stream.

### 🐛 Bug Fixes

- **Chats never loading**: Fixed DB race condition — `syncOllamaModels` shared the request DB handle which was closed before the async sync completed.
- **"Checking…" forever**: Ollama status chip now uses a dedicated `/api/ollama/ping` endpoint (2-second timeout, no DB sync). Resolves in ≤2s. Polls every 30 seconds. Auto-refreshes state when Ollama comes back online.
- **Full chat history in sidebar**: `/api/state` for regular users was returning only the active session. Now returns all sessions (pinned first, newest first, limit 50).
- **Dropdown wrong position**: Model picker dropdown now renders hidden first to measure its real height, then positions correctly above or below the trigger.
- **Offline models shown as available**: Sidebar "Approved Models" now shows only reachable models. When Ollama is offline: clear message instead of a ghost list. Model picker only lists available models.
- **Ollama timeout reduced**: Fetch timeout cut from 15s → 3s and `/api/show` from 8s → 3s — page loads fast even when Ollama is unreachable.

---

## [v2026.0.11–16] — 2026-05-17

### ✨ Features

- **Dark Claude-style composer**: Composer redesigned with `#1c1c1e` dark background, yellow accent caret, icon-only send button (↑ arrow), dark model picker dropdown.
- **Chat sidebar actions wired up**: Rename (inline input), Pin/Unpin, Delete, and session switching all fully functional via context menu (⋮ button on hover).
- **Sidebar chat history loads on page load**: All past conversations appear immediately in the sidebar.

### 🐛 Bug Fixes

- **Duplicate Auto Router element**: Removed hidden `<select id="manualModel">` that was rendering visible on some browsers. Replaced with `selectedModelId` JS variable.
- **Model picker showing only Auto Router**: `openAppModelDropdown()` was filtering by `state.approvedModels` (undefined). Now uses `allowedModels()` — all approved models appear.
- **Dashboard static nav link removed**: Decorative "Dashboard" nav link removed from topbar.

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
