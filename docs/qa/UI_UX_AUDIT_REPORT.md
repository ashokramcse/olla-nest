# Olla Nest — Frontend / UI / UX / Responsive Audit

**Date:** 2026-06-08 · **Tooling:** Playwright (Chromium headless) — harness in `e2e/`. Evidence (screenshots/traces) in `e2e/evidence/` (git-ignored; regenerate with `npx playwright test`).

## Summary
**45 E2E tests, all green** (Chromium) across 8 specs:
- `login.spec.js` (12) — login render/responsive/error-state/logout/headers.
- `admin.spec.js` (11) — **every admin tab** deep-journey (authenticated).
- `workspace.spec.js` (14) — **all 13 feature panels** deep-journey (authenticated).
- `crud-notes.spec.js` (1) — **full Notes CRUD round-trip** (create→reload-persist→update→delete), backend-verified at every step.
- `crud-tasks.spec.js` (1) — **Tasks create→read→delete round-trip**, backend-verified (also a UI guard for BUG-009).
- `crud-contacts.spec.js` (1) — **full Contacts CRUD round-trip**, backend-verified.
- `crud-calendar.spec.js` (1) — **Calendar create** via UI → backend-verified + grid dot rendered (API cleanup).
- `negative.spec.js` (4) — **error-state interception**: GET 500 / network-abort → "Failed to load" state; POST 500 → error toast; 401 → session-expiry navigation. Proves graceful degradation (no blank/broken panels, no uncaught errors).

### Accessibility (axe-core, WCAG 2.0/2.1 A + AA) — `a11y.spec.js` (4 tests)
Scanned admin-login, user-login, admin dashboard, workspace shell + notes panel. **Zero critical** violations on every page. Findings:
- **BUG-011 (nested-interactive, serious) — FIXED:** the sidebar profile/logout nested two focusable controls; restructured into siblings. workspace-shell serious 2 → 1.
- **BUG-010 (color-contrast, serious) — documented, design sign-off needed:** muted grey `#888888`/white (3.54:1) and brand yellow `#f5c800`/near-white (1.44:1) fail AA. Recommended ≥ `#767676` for muted text and a darker accent for yellow text-on-light. The a11y gate allows only this one known serious finding so it can't regress further.

Full per-page violation detail is written to `e2e/evidence/a11y-*.json` on each run.

### UX/functionality observation (OBS-003, not a bug)
The calendar **month grid renders events as anonymous dots** with no per-event title, edit, or delete control. Users can create events but cannot view details, edit, or delete them from the grid — only via the API. Recommend an event detail/edit popover and a delete affordance.

### UX observation (OBS-002, not a bug)
Notes and Tasks CRUD are driven by native `prompt()` / `confirm()` dialogs (the source notes "Simple inline edit via prompts for now"). This blocks the JS thread, can't validate input, isn't styled or screen-reader-friendly, and is awkward on mobile. Recommend replacing with in-app modal forms. Functionally correct (round-trips pass); flagged as a UX-quality improvement.

Found & fixed **3 real defects**: BUG-007 (admin console 404), BUG-008 (320px overflow), and **BUG-009** (Personal Assistant panel returned HTTP 500 — a Major product bug; see `BUG_REPORT.md`).

### Admin deep-journey (11, PASS)
Every tab (overview/models/users/access/settings/providers/connectors/sso/reports) opens, the matching view renders non-empty, **no console errors**, **no horizontal overflow**; dashboard metric cards present; Users tab loads the seeded admin from the API.

### Workspace deep-journey (14, PASS)
All 13 panels (memory/skills/notes/tasks/email/calendar/compare/cookbook/assistant/research/contacts/gallery/presets) open, render, **no console errors**, **no overflow**, and **Escape closes the modal** (a11y). The assistant panel's 500 (BUG-009) was caught and fixed here.

## E2E coverage (executed, PASS)

| # | Test | Result |
|---|---|---|
| 1–2 | Admin & user login pages load, form present (`#email`/`#password`/`#submitBtn`), password is `type=password`, **no console errors** | ✅ |
| 3–7 | Admin login responsive at **320 / 375 / 768 / 1440 / 1920px** — no horizontal overflow | ✅ |
| 8 | Invalid login → stays on `/admin-login`, surfaces an error message (error state) | ✅ |
| 9 | Valid login via **Enter key** → admin dashboard renders ("ADMIN PANEL") | ✅ |
| 10 | **Logout → `/admin-login`** (regression guard for the earlier `/login` "Not found" bug) | ✅ |
| 11 | Non-admin valid login → `/app` workspace | ✅ |
| 12 | Security headers on login document (X-Frame-Options DENY, nosniff, CSP, Referrer-Policy) | ✅ |

## Defects found & fixed

### BUG-007 — Console 404 on admin login (Minor) — FIXED
- **Found:** the "no console errors" assertion failed on `/admin-login`.
- **Root cause:** `admin-login.html` fetches `/api/dev/hints` (localhost dev-autofill), but the admin app's `@ComponentScan` `excludeFilter` blocked `DevHintsController` → 404 + browser console error on every admin login page load (dev/localhost only; production short-circuits before the fetch).
- **Fix:** moved `DevHintsController` to the **common** module and removed `DevHints` from the admin exclusion (it's a generic localhost-only helper used by both login pages). `/api/dev/hints` now returns 200 on admin for loopback callers.

### BUG-008 — Horizontal overflow at 320px (Minor) — FIXED
- **Found:** admin login had **34px horizontal overflow at 320px** (culprit `.login-topbar`, 354px wide); 375–1920px were clean.
- **Fix:** added `@media (max-width:360px)` tightening the topbar logo/right padding and hiding the logo sub-label. Re-verified: 0 overflow at 320px.

## NOT executed (recommended next)
- **Authenticated deep journeys:** every admin tab (Users/Models/Access Control/Settings/Providers/Connectors/SSO/Reports/Enterprise/MCP/Skills/Health) and every workspace panel (chat/notes/tasks/calendar/contacts/email/memory/skills/gallery/etc.) — CRUD, empty/loading/error states, modals, toasts, confirmations.
- **Accessibility:** axe-core scan, focus-trap on modals, ARIA labels, color-contrast, screen-reader names. (Basic keyboard-submit proven via test #9.)
- **PWA/service worker:** offline shell, network-first behavior, cache-busting across deploys.
- **Visual regression:** baseline snapshots per viewport.
- **Cross-browser:** Firefox/WebKit engines (only Chromium run here).
