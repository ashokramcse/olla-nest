// Negative-path E2E for admin user creation: duplicate email, server 500,
// and client-side required-field validation. Asserts the form surfaces a clear
// error and never silently "succeeds" or crashes.
const { test, expect } = require('@playwright/test');
const { ADMIN, collect, loginAdmin } = require('../helpers');

async function openAddUser(page) {
  await loginAdmin(page);
  await page.locator('.nav-item[data-tab="users"]').click();
  await expect(page.locator('#tab-users')).toBeVisible();
  await page.locator('#toggleAddUserBtn').click();
  await expect(page.locator('#addUserPanel')).toBeVisible();
}

// Disposable, valid-shaped password generated at runtime — never a committed literal.
function genPass() {
  return `E2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}-Aa1!`;
}

async function fillUser(page, { name, email, password }) {
  if (name !== undefined) await page.fill('#newUserName', name);
  if (email !== undefined) await page.fill('#newUserEmail', email);
  if (password !== undefined) await page.fill('#newUserPassword', password);
}

test('admin user-create: duplicate email -> error message, no new user', async ({ page }) => {
  const bag = collect(page);
  await openAddUser(page);
  // admin@ollanest.local already exists.
  await fillUser(page, { name: 'Dup User', email: 'admin@ollanest.local', password: genPass() });
  await page.locator('#createUserForm button[type="submit"]').click();
  // Error surfaced in #userMsg (not a success).
  await expect(page.locator('#userMsg')).toHaveClass(/error/, { timeout: 8000 });
  await expect(page.locator('#userMsg')).not.toHaveText(/created successfully/i);
  expect(bag.pageErrors).toEqual([]);
});

test('admin user-create: server 500 -> error message, no crash', async ({ page }) => {
  const bag = collect(page);
  await openAddUser(page);
  await page.route(/\/api\/admin\/users(\?|$)/, (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"server boom"}' })
      : route.continue());
  await fillUser(page, { name: 'Boom User', email: `boom${Date.now()}@test.local`, password: genPass() });
  await page.locator('#createUserForm button[type="submit"]').click();
  await expect(page.locator('#userMsg')).toHaveClass(/error/, { timeout: 8000 });
  await expect(page.locator('#userMsg')).toContainText(/boom|error|fail/i);
  expect(bag.pageErrors).toEqual([]);
});

test('admin user-create: missing required fields -> blocked by validation (no POST)', async ({ page }) => {
  await openAddUser(page);
  let posted = false;
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/admin\/users/.test(r.url())) posted = true;
  });
  // Submit with empty name + email (both `required`). HTML5 validation must block.
  await page.locator('#createUserForm button[type="submit"]').click();
  await page.waitForTimeout(1000);
  expect(posted, 'no POST fired for an invalid (empty required) form').toBeFalsy();
  // The required email field reports invalid.
  const emailValid = await page.locator('#newUserEmail').evaluate((el) => el.checkValidity());
  expect(emailValid, 'empty required email is invalid').toBeFalsy();
});

test('admin user-create: valid create succeeds + persists, then cleanup', async ({ page }) => {
  await openAddUser(page);
  const email = `e2e.admincreate${Date.now()}@test.local`;
  await fillUser(page, { name: 'E2E Created', email, password: genPass() });
  await page.locator('#createUserForm button[type="submit"]').click();
  await expect(page.locator('#userMsg')).toHaveClass(/success/, { timeout: 8000 });

  // Backend persistence: the user exists.
  const list = await page.request.get(`${ADMIN}/api/admin/users?page=1&limit=500`);
  expect(list.ok()).toBeTruthy();
  const data = await list.json();
  const users = Array.isArray(data) ? data : (data.users || data.items || []);
  const created = users.find((u) => u.email === email);
  expect(created, 'created user present in backend').toBeTruthy();

  // Cleanup: delete the disposable user.
  const del = await page.request.delete(`${ADMIN}/api/admin/users/${created.id}`,
    { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
  expect(del.ok()).toBeTruthy();
});
