// E2E test credentials — LOCAL OVERRIDE TEMPLATE.
//
// These are disposable credentials for the locally-seeded test database only.
// They must NEVER be committed as literals (GitGuardian flags committed
// passwords regardless of whether they are "real").
//
// Usage:
//   1. Copy this file to `.e2e-creds.js` (which is .gitignored), OR
//   2. Export the env vars below before running Playwright/k6.
//
// helpers.js / login.spec.js / k6 resolve credentials in this order:
//   process.env.*  ->  ./.e2e-creds.js  ->  hard failure (no committed default)
module.exports = {
  ADMIN_EMAIL: 'admin@ollanest.local',
  ADMIN_PASS:  'CHANGE_ME_ADMIN_PASS',
  QA_EMAIL:    'qa.user@test.local',
  QA_PASS:     'CHANGE_ME_QA_PASS',
};
