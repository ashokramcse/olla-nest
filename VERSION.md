# Version Tracker — Olla Nest

Tracks every release, version tag, and key commit in the project history.

---

## Current Version

| Field | Value |
|---|---|
| **Version** | `v2026.0.4` |
| **Released** | 2026-05-17 |
| **Commit** | `0a866fc` |
| **Status** | ✅ Stable |
| **Branch** | `main` |

---

## Version History

### v2026.0.4 — 2026-05-17
> **Inline employee editor + human-readable permissions**

| Commit | Date | Description |
|---|---|---|
| `0a866fc` | 2026-05-17 | feat: inline edit panel for employees with permission checkboxes and human-readable labels |

### v2026.0.3 — 2026-05-17
> **Security hardening + performance + bug fixes** (post-audit release)

| Commit | Date | Description |
|---|---|---|
| `ca6044e` | 2026-05-17 | fix: security hardening, DB indexes, rate limit enforcement, XSS sanitization |
| `c84e351` | 2026-05-17 | docs: v2026.0.2 changelog entry |
| `7e6831f` | 2026-05-17 | feat: separate admin/employee login, model status + token usage UI |

### v2026.0.2 — 2026-05-17
> **Separate logins, model status, token usage**

| Commit | Date | Description |
|---|---|---|
| `7e6831f` | 2026-05-17 | feat: separate admin/employee login pages, model status pill, daily token usage bar |

### v2026.0.1.mvp — 2026-05-17
> **First public MVP release**

| Commit | Date | Description |
|---|---|---|
| `e9cf007` | 2026-05-17 | security: harden app — rate limiting, CSRF headers, security headers, path restrictions |
| `3e79d12` | 2026-05-16 | ux: remove mode bar from admin chat — always uses ask mode |
| `2ae7347` | 2026-05-16 | ux: remove mode bar from chat — users just type, router handles the rest |
| `1770136` | 2026-05-16 | fix: Admin UI — 6 issues: layout, providers tab, leaderboard pagination, chart quality |
| `a8be484` | 2026-05-16 | feat: Reports tab with 10 branded interactive charts + token leaderboard |
| `31675a7` | 2026-05-16 | feat: 4 fixes — model staleness, card parity, invite modal, team mgmt |
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
| `2a97f04` | 2026-05-15 | fix: use bind mount ./data so workspace files are visible on Mac |
| `aa8fb9a` | 2026-05-15 | fix: add credentials:include to folder browser fetch |
| `93ef0e1` | 2026-05-15 | fix: Claude-like chat flow, file saving, instant new chat, elapsed time |
| `7680be3` | 2026-05-15 | fix: mark tcm-delete callback as async to allow await ollaConfirm |
| `7af4b42` | 2026-05-15 | fix: add 5s timeout to fetchOllamaModels to prevent UI freeze |
| `c4259dc` | 2026-05-15 | fix: files now always save to workspace regardless of chat mode |
| `869a78e` | 2026-05-15 | feat: branded dialog system replacing all native confirm/alert/prompt |
| `4d85ee5` | 2026-05-15 | feat: Finder-style folder picker with sidebar, grid view, back btn, new folder |
| `c875948` | 2026-05-15 | feat: live elapsed timer in typing indicator |
| `5c1b239` | 2026-05-15 | fix: Pin, Mark as unread, Rename, Fork all work on active thread |
| `a2ae6a8` | 2026-05-15 | fix: folder picker always shows filesystem browser, falls back to home dir |
| `308045d` | 2026-05-15 | fix: remove Open in new tab from thread context menu |
| `c10e094` | 2026-05-15 | fix: optimistic message display, typing indicator, thread history, auto-save |
| `74c37b3` | 2026-05-15 | feat: chat history sidebar with thread management |
| `377a815` | 2026-05-15 | fix: remove duplicate Auto Router dropdown below chat |
| `46c2af9` | 2026-05-15 | fix: dropdown opens left-aligned to trigger + slash Enter no longer submits form |
| `ce246a3` | 2026-05-15 | feat: Claude-like workspace — files saved in project folder with filenames from model |
| `3c6368a` | 2026-05-15 | fix: Compact model bar + increase Ollama timeout to 5 minutes |
| `aa5fcd3` | 2026-05-15 | feat: Claude-style model selector with context window bar |
| `71e798a` | 2026-05-15 | feat: Replace path input with real filesystem folder browser |
| `b1ac3c4` | 2026-05-15 | feat: Add folder as real local project workspace selector |
| `2e33c28` | 2026-05-14 | feat: full enterprise-grade admin dashboard — RBAC, governance, audit trail |
| `8d97a8f` | 2026-05-14 | fix: Ollama status clarity, default URL, test connection model list |
| `68bde47` | 2026-05-14 | fix: 6 admin/workspace issues: remove dead cards, button-triggered forms, multi-provider |
| `74c05b6` | 2026-05-14 | chore: project cleanup — remove all dead code and tighten control |
| `777c497` | 2026-05-14 | fix: remove old public/index.html that was overriding new UI |
| `24122ec` | 2026-05-14 | feat: Move to Docker-only runtime and rebuild UI from scratch |
| `4038f6a` | 2026-05-13 | feat: Add explicit local workspace permissions |
| `c9b5ece` | 2026-05-13 | feat: Add local workspace artifact writes |
| `ae51d48` | 2026-05-13 | fix: model source persistence and build responses |
| `a5a1ccd` | 2026-05-13 | feat: Add configurable model sources |
| `d29a5bf` | 2026-05-13 | fix: workspace navigation and model refresh UX |
| `c61f5dd` | 2026-05-13 | feat: Replace UI with TailAdmin React design system |
| `9422622` | 2026-05-13 | feat: Refactor UI with TailAdmin-inspired layout |
| `db69536` | 2026-05-13 | feat: Polish Material UI layout and empty states |
| `3e76026` | 2026-05-13 | feat: Refactor UI to React MUI and Tailwind |
| `3d2ae80` | 2026-05-13 | feat: Add account rights and Docker-first deployment |
| `c996a56` | 2026-05-13 | feat: Split admin and user experiences with bootstrap login |
| `be3e737` | 2026-05-13 | feat: Define production database and deployment architecture |
| `80e765b` | 2026-05-13 | refactor: Refactor routing around dynamic models and hybrid storage |
| `91c2312` | 2026-05-13 | feat: Build Olla Nest MVP |
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
| `PATCH` | `1` | Bug fix / hotfix increment |
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
| `v2026.1.0` | Q3 2026 | SSO/LDAP integration, RAG document store, department policy editor |
| `v2026.2.0` | Q4 2026 | API provider billing, usage analytics per employee, mobile PWA |
| `v2027.0.0` | Q1 2027 | Desktop app (Tauri), multi-tenant, plugin system |

---

*Full changelog: [CHANGELOG.md](CHANGELOG.md)*  
*GitHub releases: [github.com/ashokramcse/olla-nest/releases](https://github.com/ashokramcse/olla-nest/releases)*
