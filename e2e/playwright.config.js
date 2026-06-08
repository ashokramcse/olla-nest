// Playwright E2E config for Olla Nest (admin 8080 + user 8081 already running).
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 30000,
  expect: { timeout: 8000 },
  retries: 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: 'results.json' }]],
  use: {
    headless: true,
    ignoreHTTPSErrors: true,
    screenshot: 'on',
    trace: 'retain-on-failure',
    actionTimeout: 8000,
  },
  outputDir: 'evidence',
});
