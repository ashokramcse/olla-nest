# Olla Nest — Frontend / UI / UX / Responsive Audit

**Date:** 2026-06-08 · **Tooling:** Playwright (Chromium headless) — harness in `e2e/`. Evidence (screenshots/traces) in `e2e/evidence/` (git-ignored; regenerate with `npx playwright test`).

## Summary
**12 E2E tests, all green** after fixing 2 real frontend defects found during the audit. Login journeys for both apps, responsive behavior (320–1920px), error states, the logout regression, and security headers are all proven.

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
