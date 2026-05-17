# Version Tracker — Olla Nest

Tracks every release, version tag, and key commit in the project history.

---

## Current Version

| Field | Value |
|---|---|
| **Version** | `v2026.0.10` |
| **Released** | 2026-05-17 |
| **Commit** | `5894b4a` |
| **Status** | ✅ Stable |
| **Branch** | `main` |

---

## Version History

### v2026.0.10 — 2026-05-17
> **Full external provider support — no hardcoded model names**

| Commit | Date | Description |
|---|---|---|
| `5894b4a` | 2026-05-17 | feat: full external provider support — resolveProvider, dynamic test, Anthropic real API, mirror api_models |

**Key changes:**
- `resolveProvider(db, route)` — single helper for all provider types in both chat endpoints
- `/api/admin/providers/:id/test` — uses first real model from `api_models`, never hardcoded names
- Anthropic sync calls real `/v1/models` API instead of static list
- `mirrorApiModelToModels()` — approved external models sync into main `models` table for router visibility
- `getModelContextWindow()` — checks `api_models.context_window` for external providers

---

### v2026.0.9 — 2026-05-17
> **Model-agnostic context window — real Ollama /api/show lookup**

| Commit | Date | Description |
|---|---|---|
| `8229f05` | 2026-05-17 | fix: fully model-agnostic context window + real Ollama /api/show lookup |

**Key changes:**
- Removed all hardcoded model name patterns from `contextWindowForModel()`
- Queries Ollama `/api/show` for real context length; cached in `models.context_size`
- Universal 8192-token fallback for any model without a known context size

---

### v2026.0.8 — 2026-05-17
> **Chat session memory + file upload + dropdown fix**

| Commit | Date | Description |
|---|---|---|
| `aa4d046` | 2026-05-17 | feat: chat context history, file upload, dropdown fix |

**Key changes:**
- Sliding-window chat history: loads all prior messages from DB, trims to model context limit
- `buildSystemPrompt()` / `buildContextMessages()` / `estimateTokens()` helpers
- File upload: images (base64 → Ollama multimodal) + text files (code block append), up to 5 attachments
- Model picker dropdown: changed to `position:fixed` + JS rect positioning to escape `overflow:hidden`

---

### v2026.0.7 — 2026-05-17
> **4 UX fixes — auto-save governance, Claude-style model picker**

| Commit | Date | Description |
|---|---|---|
| `ef8e349` | 2026-05-17 | feat: 4 UX fixes — centered save btn, auto-save governance, Claude-style model picker |
| `51882fe` | 2026-05-17 | fix: Ollama URL save — test after save, fix header, remove false LAN warning |

---

### v2026.0.6 — 2026-05-17
> **Branded toasts + confirms, CSP fix, Ollama sync diagnosis, permission groups**

| Commit | Date | Description |
|---|---|---|
| `b2d2184` | 2026-05-17 | feat: branded toast + confirm dialogs, fix Ollama URL detection |
| `d33eddd` | 2026-05-17 | fix: Ollama sync diagnosis + CSP fix for admin markdown |
| `b37e969` | 2026-05-17 | fix: dead code cleanup, state.me bug, console.error removal |
| `e476b7c` | 2026-05-17 | fix: remove Security Policies card, expand Department Defaults |
| `ba5d4cd` | 2026-05-17 | fix: remove Active Sessions card, expand User Access |
| `731cf19` | 2026-05-17 | feat: redesign Access Control + fix Ollama sync + permission groups |

---

### v2026.0.5 — 2026-05-17
> **Docker cache busting**

| Commit | Date | Description |
|---|---|---|
| `4e7c6d7` | 2026-05-17 | fix: reduce JS/CSS cache TTL from 1 day to 5 minutes |
| `e776491` | 2026-05-17 | fix: cache-bust admin.js and app.js with version query string |

---

### v2026.0.4 — 2026-05-17
> **Inline employee editor + human-readable permissions**

| Commit | Date | Description |
|---|---|---|
| `0a866fc` | 2026-05-17 | feat: inline edit panel for employees with permission checkboxes and human-readable labels |

---

### v2026.0.3 — 2026-05-17
> **Security hardening + performance indexes + bug fixes**

| Commit | Date | Description |
|---|---|---|
| `ca6044e` | 2026-05-17 | fix: security hardening, DB indexes, rate limit enforcement, XSS sanitization |
| `c84e351` | 2026-05-17 | docs: v2026.0.2 changelog entry |
| `7e6831f` | 2026-05-17 | feat: separate admin/employee login, model status + token usage UI |

---

### v2026.0.2 — 2026-05-17
> **Separate logins, model status, token usage, terminal**

| Commit | Date | Description |
|---|---|---|
| `7e6831f` | 2026-05-17 | feat: separate admin/employee login pages, model status pill, daily token usage bar |

---

### v2026.0.1.mvp — 2026-05-17
> **First public MVP release**

| Commit | Date | Description |
|---|---|---|
| `e9cf007` | 2026-05-17 | security: harden app — rate limiting, CSRF headers, security headers, path restrictions |
| `3e79d12` | 2026-05-16 | ux: remove mode bar from admin chat |
| `2ae7347` | 2026-05-16 | ux: remove mode bar from chat |
| `1770136` | 2026-05-16 | fix: Admin UI — 6 issues: layout, providers tab, leaderboard pagination, chart quality |
| `a8be484` | 2026-05-16 | feat: Reports tab with 10 branded interactive charts + token leaderboard |
| `31675a7` | 2026-05-16 | feat: 4 fixes — model staleness, card parity, invite modal, team management |
| `38a9a79` | 2026-05-16 | feat: user profile drawer with enterprise-aware field locking |
| `cc11175` | 2026-05-16 | fix: remove infinite-recursion wrapper pattern in admin.js |
| `3db9f68` | 2026-05-16 | fix: switch SSE abort listener from req.close to res.close |
| `374dafc` | 2026-05-16 | feat: major platform upgrade — SQLite migration, API providers, SSE streaming, router intelligence |
| `ae20838` | 2026-05-16 | feat: SQLite chat store, API provider integration, SSE streaming, router intelligence, feedback |
| `af9d477` | 2026-05-16 | feat: beautiful chat UI with syntax-highlighted code blocks |
| `69da61b` | 2026-05-15 | fix: remove password leak from /api/bootstrap + auto-refresh Ollama status |
| `0e8fee6` | 2026-05-15 | fix: persist extractedFiles in message so download chips work on all messages |
| `680227c` | 2026-05-15 | fix: show browser-appropriate UI in folder modal (Firefox vs Chrome) |
| `9eabb7f` | 2026-05-15 | fix: cross-browser file saving via download chips (Mac/Windows/Linux) |
| `611ece2` | 2026-05-15 | feat: client-side file writes — files save to user's own computer |
| `335902d` | 2026-05-15 | fix: mount Mac home into container so files save to real Mac folders |
| `758a6a4` | 2026-05-15 | fix: rebuild chat history sidebar from scratch |
| `93ef0e1` | 2026-05-15 | fix: Claude-like chat flow, file saving, instant new chat, elapsed time |
| `869a78e` | 2026-05-15 | feat: branded dialog system replacing all native confirm/alert/prompt |
| `4d85ee5` | 2026-05-15 | feat: Finder-style folder picker with sidebar, grid view, back btn, new folder |
| `74c37b3` | 2026-05-15 | feat: chat history sidebar with thread management |
| `aa5fcd3` | 2026-05-15 | feat: Claude-style model selector with context window bar |
| `2e33c28` | 2026-05-14 | feat: full enterprise-grade admin dashboard — RBAC, governance, audit trail |
| `24122ec` | 2026-05-14 | feat: Move to Docker-only runtime and rebuild UI from scratch |
| `518fb85` | 2026-05-05 | chore: first commit |

---

## Versioning Scheme

```
v{YEAR}.{MINOR}.{PATCH}[.{STAGE}]
```

| Part | Example | Meaning |
|---|---|---|
| `YEAR` | `2026` | Calendar year of release |
| `MINOR` | `0` | Feature increment within the year |
| `PATCH` | `10` | Bug fix / hotfix increment |
| `STAGE` | `mvp` | Optional: `mvp`, `beta`, `rc`, `lts` |

### Examples

| Tag | Meaning |
|---|---|
| `v2026.0.1.mvp` | First MVP release, 2026 |
| `v2026.1.0` | First feature release after MVP |
| `v2026.1.1` | Patch on top of 2026.1.0 |
| `v2026.2.0.beta` | Beta of second major feature drop |
| `v2027.0.0` | New year, new baseline |

---

## Planned Future Releases

| Version | Target | Highlights |
|---|---|---|
| `v2026.1.0` | Q3 2026 | SSO/LDAP integration, RAG document store, department policy editor UI |
| `v2026.2.0` | Q4 2026 | Usage analytics per employee, API provider billing, mobile PWA |
| `v2027.0.0` | Q1 2027 | Desktop app (Tauri), multi-tenant, plugin system |

---

*Full changelog: [CHANGELOG.md](CHANGELOG.md)*  
*GitHub releases: [github.com/ashokramcse/olla-nest/releases](https://github.com/ashokramcse/olla-nest/releases)*
