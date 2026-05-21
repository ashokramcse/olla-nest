# Version Tracker — Olla Nest

Tracks every release, version tag, and key commit in the project history.

---

## Current Version

| Field | Value |
|---|---|
| **Version** | `v2026.0.28` |
| **Released** | 2026-05-21 |
| **Commit** | *(pending)* |
| **Status** | ✅ Stable |
| **Branch** | `main` |

---

## Version History

### v2026.0.28 — 2026-05-21
> **CSS dead code purge, icon branding fix, logo centering, version catch-up**

**Key changes:**
- Removed ~280 lines of dead CSS: hero section, admin sidebar layout, infographic ring/gauge/sparkline, `.account-panel`, `.settings-grid`, `.welcome-bar`, `.stats-bar`, `.theme-controls` block, `.logo-mark`, `.app-shell`, `.user-avatar-wrap`, `.model-select`
- **Critical bug fix**: `.login-brand-sub { display:none }` was hiding real content on login pages
- Fixed `.page` duplicate width rule — consolidated to `width:100%; max-width:1440px`
- Fixed `.nav-item` sidebar-specific `border-left` removed from base rule (no more `!important` battles in admin tab-bar)
- Hardcoded colours replaced: `.app-model-dot` `#4caf50` → `var(--success)`, `.status-dot.off` `#aaa` → `var(--muted2)`
- Font size audit: `8px`/`9px` labels upgraded to `10px` minimum (`.topnav-stat-label`, `.logo-sub`)
- Duplicate `.rpt-kpi-card` definition removed from admin.html; dead chart-row CSS removed
- **Empty state icon** (✦ "Ready when you are") now uses `var(--ac)` as solid fill and `var(--ac-text)` for icon colour — automatically follows user theme changes
- `logo-readme.svg` content centered within the SVG viewport

---

### v2026.0.27 — 2026-05-20
> **Branding assets, header overlap fix, architecture SVG fix, reports → tables**

| Commit | Date | Description |
|---|---|---|
| `a3bb701` | 2026-05-20 | Fix architecture SVG overlaps, bigger README logo, replace reports charts with tables |
| `d6fac76` | 2026-05-20 | Fix header overlap and layout issues across app and admin |
| `1c62743` | 2026-05-20 | Add branding assets, favicon, logo, and updated docs |

**Key changes:**
- Architecture SVG widened 760→820px; all three boxes (LOCAL/CLOUD/ADMIN) no longer overlap
- README logo `width` 280→480px for high-DPI screens
- Reports tab rebuilt from scratch: 9 Chart.js canvases replaced with 10 structured `<table class="data-table">` elements
- `overflow:hidden` on `.shell` changed to `overflow:clip` — fixes sticky topnav
- `padding-top:20px` added to `.main-grid` and all `.tab-view.active` panels
- Chat height changed to `calc(100vh - 200px)` from fixed `680px`
- Router card sticky top updated to `calc(56px + 20px)`
- Profile card colours fixed for day mode (was dark olive gradient)

---

### v2026.0.26 — 2026-05-20
> **Per-user theme storage, System mode, theme controls moved to Settings**

| Commit | Date | Description |
|---|---|---|
| `30def9f` | 2026-05-20 | Move theme controls to Settings; per-user storage; add System mode |
| `9c937e8` | 2026-05-20 | Login: center both panels, bigger eyebrow, center-align brand text |
| `6f7808f` | 2026-05-20 | Login pages: Option A layout — 50/50 split with floating card |
| `cb9cf94` | 2026-05-20 | Add Olla Nest logo and fix login pages to fill viewport |
| `4e76643` | 2026-05-20 | Redesign login pages (full-page split) and dashboard reports tab |

**Key changes:**
- Theme preferences stored per user in localStorage (`themeHex_u_${userId}` / `themeMode_u_${userId}`)
- System mode added: follows OS `prefers-color-scheme`
- Theme controls moved from persistent topbar bar into profile drawer → Settings
- Login redesigned: full-page 50/50 split — dark brand panel left, card right
- Olla Nest logo SVG added with CSS-var-driven colours

---

### v2026.0.25 — 2026-05-20
> **Full theme audit, night mode surface fix**

| Commit | Date | Description |
|---|---|---|
| `256fc49` | 2026-05-20 | Full theme audit: replace all hardcoded colors with CSS vars across all files |
| `69b7b8c` | 2026-05-20 | Fix night mode surface hierarchy and make all charts theme-aware |

**Key changes:**
- Every hardcoded hex colour in HTML/JS/CSS replaced with semantic CSS variables
- Night mode surface hierarchy corrected: bg → shell → card layering consistent
- All Chart.js charts updated to read from `getComputedStyle(document.documentElement)` at render time

---

### v2026.0.24 — 2026-05-20
> **Design system refactor — theme.js token engine, UX audit fixes, code cleanup**

| Commit | Date | Description |
|---|---|---|
| `3a90500` | 2026-05-20 | Code cleanup: purge all legacy design tokens across HTML/JS/CSS |
| `378a0ef` | 2026-05-20 | Fix 12-phase UX audit: viewport, legacy vars, security, responsive CSS |
| `7baf557` | 2026-05-20 | Fix admin tab-bar break + redesign login pages to match dashboard |
| `2fade10` | 2026-05-20 | Design system refactor: theme.js engine, new CSS variables, updated all pages |

**Key changes:**
- `theme.js` introduced: `applyTheme(hex, mode)` writes ~25 design tokens to `:root` at runtime
- All `--yellow-*`, `--black`, `--card`, `--ink-*` legacy tokens replaced
- Admin tab-bar restored after design system refactor
- 12-phase UX audit fixes: viewport meta, missing outline, responsive breakpoints

---

### v2026.0.23 — 2026-05-20
> **Cross-platform workspace, folder browser, OS detection**

| Commit | Date | Description |
|---|---|---|
| `34c22d2` | 2026-05-20 | fix: workspace path validation — reject host paths, show container path hint in UI |
| `0cbc246` | 2026-05-20 | feat: make mac-home writable so employees can save files to their own machine folders |
| `a7dfe2f` | 2026-05-20 | feat: cross-platform workspace — rename mac-home to host-home, add folder browser, OS detection |
| `1042b22` | 2026-05-20 | fix: workspace panel always shows OS hint and replaces invalid saved paths |
| `b668b7c` | 2026-05-20 | fix: remove missing sourceMappingURL from chart.umd.min.js |
| `ed9f8b5` | 2026-05-20 | fix: add missing showConfirm dialog — fixes crash that blocked New Chat |

**Key changes:**
- Workspace volume renamed `mac-home` → `host-home` for cross-platform clarity
- In-app folder browser added to workspace config panel
- OS detection for path hints (macOS, Windows, Linux)
- `showConfirm` was missing from app.js — caused a crash that broke New Chat

---

### v2026.0.22 — 2026-05-19
> **Thinking indicator, code review modal, project knowledge, input history, settings bug fix**

| Commit | Date | Description |
|---|---|---|
| `7c5eb83` | 2026-05-19 | fix: correct settings route path — POST /api/admin/settings was routing to 404 |
| `2c723d6` | 2026-05-19 | feat: thinking indicator, code review modal, project knowledge context, input history |

**Key changes:**
- Thinking indicator: animated dots with Routing → Thinking phase labels before first token
- Code review modal: full-screen overlay with hljs highlighting, line count, plain-text copy
- Project Knowledge: admin-set context injected into every chat system prompt
- Input history: ↑/↓ terminal-style navigation of sent messages; draft preserved
- Bug fix: `POST /api/admin/settings` was never reachable — router mounted at wrong path

---

### v2026.0.21 — 2026-05-19
> **Code block overhaul — syntax highlighting, line numbers, diff view, language badges**

| Commit | Date | Description |
|---|---|---|
| `5c96044` | 2026-05-19 | feat: add enhanced code block rendering with syntax highlighting and diff view |

**Key changes:**
- highlight.js loaded for 30+ languages
- Per-language colour-coded badge (JS=yellow, TS=blue, Python=green, Rust=salmon…)
- Line numbers via table-cell layout — not included in clipboard copy
- Diff rendering: `+` green, `-` red, `@@` blue hunk header
- Filename header extracted from `// filename:` comment
- New dark header bar: badge + filename + ⛶ View + Copy + Run

---

### v2026.0.17 — 2026-05-17
> **Security audit fixes + full modular refactor**

| Commit | Date | Description |
|---|---|---|
| `df462e2` | 2026-05-17 | fix: apply 10 security and reliability improvements from audit |
| `8f93c87` | 2026-05-17 | refactor: split monolithic server.js into modular src/ structure |
| `2930a38` | 2026-05-17 | refactor: move Ollama sync out of HTTP handlers into background timer |

**Key changes:**
- 10 security fixes: host FS read-only, no password leak in bootstrap, PTY secrets scrubbed, token quotas enforced, sensitive content on manual model, role allowlist, non-root user
- Performance: DDL out of openSql(), 2 new indexes, chat insert transactions
- Full modular split: 31 files under src/, server.js is 41 lines

---

### v2026.0.11–16 — 2026-05-17
> **UI/UX fixes, chat history, Ollama offline handling**

| Commit | Date | Description |
|---|---|---|
| `various` | 2026-05-17 | Dark composer redesign, sidebar chat history, Ollama ping endpoint, offline model handling, dropdown positioning, duplicate Auto Router fix |

---

### v2026.0.10 — 2026-05-17
> **Full external provider support — no hardcoded model names**

| Commit | Date | Description |
|---|---|---|
| `5894b4a` | 2026-05-17 | feat: full external provider support — resolveProvider, dynamic test, Anthropic real API, mirror api_models |

---

### v2026.0.9 — 2026-05-17
> **Model-agnostic context window — real Ollama /api/show lookup**

| Commit | Date | Description |
|---|---|---|
| `8229f05` | 2026-05-17 | fix: fully model-agnostic context window + real Ollama /api/show lookup |

---

### v2026.0.8 — 2026-05-17
> **Chat session memory + file upload + dropdown fix**

| Commit | Date | Description |
|---|---|---|
| `aa4d046` | 2026-05-17 | feat: chat context history, file upload, dropdown fix |

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
| `731cf19` | 2026-05-17 | feat: redesign Access Control + fix Ollama sync + permission groups |

---

### v2026.0.5 — 2026-05-17
> **Docker cache busting**

| Commit | Date | Description |
|---|---|---|
| `4e7c6d7` | 2026-05-17 | fix: reduce JS/CSS cache TTL from 1 day to 5 minutes |

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

---

### v2026.0.2 — 2026-05-17
> **Separate logins, model status, token usage, terminal**

| Commit | Date | Description |
|---|---|---|
| `7e6831f` | 2026-05-17 | feat: separate admin/employee login pages, model status pill, daily token usage bar |

---

### v2026.0.1.mvp — 2026-05-16
> **First public MVP release**

| Commit | Date | Description |
|---|---|---|
| `9ca6aa8` | 2026-05-16 | docs: update README, add CHANGELOG and VERSION tracker for v2026.0.1.mvp |

---

## Versioning Scheme

```
v{YEAR}.{MINOR}.{PATCH}[.{STAGE}]
```

| Part | Example | Meaning |
|---|---|---|
| `YEAR` | `2026` | Calendar year of release |
| `MINOR` | `0` | Feature increment within the year |
| `PATCH` | `28` | Bug fix / hotfix increment |
| `STAGE` | `mvp` | Optional: `mvp`, `beta`, `rc`, `lts` |

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
