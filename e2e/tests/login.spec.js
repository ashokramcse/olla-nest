// OCD E2E: login pages, auth flows, logout regression, error/empty/loading states,
// responsive, and console-error detection. Evidence = screenshots in e2e/evidence/.
const { test, expect } = require('@playwright/test');

const ADMIN = 'http://localhost:8080';
const USER = 'http://localhost:8081';
const ADMIN_EMAIL = 'admin@ollanest.local';
const ADMIN_PASS = 'REDACTED_TEST_CRED';
const QA_EMAIL = 'qa.user@test.local';
const QA_PASS = 'REDACTED_TEST_CRED';

// Collect console errors + failed requests per page.
function attachDiagnostics(page, bag) {
  page.on('console', (m) => { if (m.type() === 'error') bag.consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => bag.pageErrors.push(String(e)));
  page.on('requestfailed', (r) => bag.failedRequests.push(`${r.method()} ${r.url()} :: ${r.failure()?.errorText}`));
}

test.describe('Login pages render', () => {
  for (const [name, base, loginPath] of [['admin', ADMIN, '/admin-login'], ['user', USER, '/login']]) {
    test(`${name} login page loads with form + no console errors`, async ({ page }, testInfo) => {
      const bag = { consoleErrors: [], pageErrors: [], failedRequests: [] };
      attachDiagnostics(page, bag);
      const resp = await page.goto(base + loginPath, { waitUntil: 'domcontentloaded' });
      expect(resp.status()).toBe(200);
      await expect(page.locator('#email')).toBeVisible();
      await expect(page.locator('#password')).toBeVisible();
      await expect(page.locator('#submitBtn')).toBeVisible();
      // password field must be type=password (not leaking plaintext)
      await expect(page.locator('#password')).toHaveAttribute('type', 'password');
      await page.screenshot({ path: `evidence/${name}-login.png`, fullPage: true });
      expect(bag.pageErrors, 'no uncaught page errors').toEqual([]);
      // ignore favicon-type benign failures; assert no app JS console errors
      const appErrors = bag.consoleErrors.filter((e) => !/favicon|manifest/i.test(e));
      expect(appErrors, `console errors: ${appErrors.join(' | ')}`).toEqual([]);
    });
  }
});

test.describe('Responsive login (admin)', () => {
  for (const vp of [{ w: 320, h: 640 }, { w: 375, h: 812 }, { w: 768, h: 1024 }, { w: 1440, h: 900 }, { w: 1920, h: 1080 }]) {
    test(`admin login at ${vp.w}x${vp.h} has no horizontal overflow`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h });
      await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
      // No horizontal scrollbar = no broken responsive layout
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `horizontal overflow ${overflow}px at ${vp.w}px`).toBeLessThanOrEqual(2);
      await page.screenshot({ path: `evidence/admin-login-${vp.w}.png` });
    });
  }
});

test('admin: invalid login shows error state, no redirect', async ({ page }) => {
  await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', ADMIN_EMAIL);
  await page.fill('#password', 'WRONG-PASSWORD');
  await page.click('#submitBtn');
  // Stays on login page; an error message becomes visible somewhere
  await page.waitForTimeout(1500);
  expect(page.url()).toContain('/admin-login');
  await page.screenshot({ path: 'evidence/admin-login-invalid.png', fullPage: true });
  const body = (await page.locator('body').innerText()).toLowerCase();
  expect(body, 'an error/invalid message is surfaced').toMatch(/invalid|incorrect|wrong|error|failed|try again/);
});

test('admin: valid login -> dashboard renders (Enter key submits)', async ({ page }) => {
  const bag = { consoleErrors: [], pageErrors: [], failedRequests: [] };
  attachDiagnostics(page, bag);
  await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', ADMIN_EMAIL);
  await page.fill('#password', ADMIN_PASS);
  await page.press('#password', 'Enter'); // keyboard submit
  await page.waitForURL(/\/admin(\b|$|\?)/, { timeout: 15000 });
  await expect(page.locator('text=ADMIN PANEL').first()).toBeVisible({ timeout: 10000 });
  await page.screenshot({ path: 'evidence/admin-dashboard.png', fullPage: true });
  expect(bag.pageErrors).toEqual([]);
});

test('admin: logout returns to /admin-login (regression for /login bug)', async ({ page }) => {
  await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', ADMIN_EMAIL);
  await page.fill('#password', ADMIN_PASS);
  await page.click('#submitBtn');
  await page.waitForURL(/\/admin(\b|$|\?)/, { timeout: 15000 });
  await page.click('#logoutBtn');
  await page.waitForURL(/\/admin-login/, { timeout: 10000 });
  expect(page.url()).toContain('/admin-login');
  // Must NOT be the JSON "Not found" page
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Not found');
  await page.screenshot({ path: 'evidence/admin-after-logout.png', fullPage: true });
});

test('user: valid non-admin login -> workspace app', async ({ page }) => {
  await page.goto(USER + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', QA_EMAIL);
  await page.fill('#password', QA_PASS);
  await page.click('#submitBtn');
  await page.waitForURL(/\/app(\b|$|\?)/, { timeout: 15000 });
  await page.screenshot({ path: 'evidence/user-app.png', fullPage: true });
  expect(page.url()).toContain('/app');
});

test('security headers present on login document', async ({ page }) => {
  const resp = await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
  const h = resp.headers();
  expect(h['x-frame-options']).toBe('DENY');
  expect(h['x-content-type-options']).toBe('nosniff');
  expect(h['content-security-policy']).toBeTruthy();
  expect(h['referrer-policy']).toBeTruthy();
});
