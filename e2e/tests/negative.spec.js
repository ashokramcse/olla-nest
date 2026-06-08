// Negative / error-state E2E via network interception (page.route).
// Asserts the app degrades gracefully: error state on load failure, error toast
// on mutation failure, and session-expiry redirect on 401 — never a blank/broken
// panel or an uncaught page error.
const { test, expect } = require('@playwright/test');
const { collect, loginUser } = require('../helpers');

async function openNotes(page) {
  await page.evaluate(() => window.openFeaturePanel('notes'));
  await expect(page.locator('#fp-notes')).toHaveClass(/open/);
  // Force a fresh load under the (already-armed) route so the interception is
  // deterministically exercised — avoids a race with the panel's auto-load.
  await page.evaluate(() => window.loadNotes && window.loadNotes());
}

test('GET /api/notes 500 -> panel shows "Failed to load" error state (not blank/crash)', async ({ page }) => {
  const bag = collect(page);
  await loginUser(page);
  await page.route(/\/api\/notes(\?|$)/, (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' });
    }
    return route.continue();
  });
  await openNotes(page);
  await expect(page.locator('#notesList')).toContainText(/Failed to load/i, { timeout: 6000 });
  // Graceful: no uncaught JS error took down the page.
  expect(bag.pageErrors, `pageErrors: ${bag.pageErrors.join(' | ')}`).toEqual([]);
});

test('GET /api/notes network abort -> graceful error state', async ({ page }) => {
  const bag = collect(page);
  await loginUser(page);
  await page.route(/\/api\/notes(\?|$)/, (route) => {
    if (route.request().method() === 'GET') return route.abort();
    return route.continue();
  });
  await openNotes(page);
  await expect(page.locator('#notesList')).toContainText(/Failed to load/i, { timeout: 6000 });
  expect(bag.pageErrors).toEqual([]);
});

test('POST /api/notes 500 -> error toast shown, no crash', async ({ page }) => {
  const bag = collect(page);
  const answers = ['neg-note-title', 'neg-note-content'];
  page.on('dialog', (d) => (d.type() === 'confirm' ? d.accept() : d.accept(String(answers.shift() ?? ''))));
  await loginUser(page);
  await page.route(/\/api\/notes(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' });
    }
    return route.continue();
  });
  await openNotes(page);
  await page.locator('#fp-notes').getByRole('button', { name: '+ Note' }).click();
  // showToast appends a transient div with the failure message.
  await expect(page.getByText('Failed', { exact: false })).toBeVisible({ timeout: 6000 });
  expect(bag.pageErrors).toEqual([]);
});

test('GET /api/notes 401 -> session-expiry handler navigates away (panel closes)', async ({ page }) => {
  await loginUser(page);
  // Open the panel first (proves it's open), then arm the 401.
  await page.evaluate(() => window.openFeaturePanel('notes'));
  await expect(page.locator('#fp-notes')).toHaveClass(/open/);

  // A 401 from any API call triggers api()'s `window.location.href = "/login"`.
  // The still-valid session 302-bounces /login -> /app, but the key observable
  // effect is a FULL navigation (the SPA reloads), which tears down the open
  // panel overlay. If the redirect had NOT fired, the panel would stay open.
  let navigated = false;
  page.on('framenavigated', (f) => { if (f === page.mainFrame()) navigated = true; });
  await page.route(/\/api\/notes(\?|$)/, (route) =>
    route.request().method() === 'GET'
      ? route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' })
      : route.continue());
  // Re-trigger a notes load to hit the armed 401.
  await page.evaluate(() => window.loadNotes && window.loadNotes());
  await page.waitForTimeout(2500);
  expect(navigated, 'a full navigation (session-expiry redirect) occurred').toBeTruthy();
  await expect(page.locator('#fp-notes')).not.toHaveClass(/open/);
});
