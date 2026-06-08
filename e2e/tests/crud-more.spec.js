// CRUD round-trips for Memory, Skills, Gallery (album), and Email (account) —
// each via the real UI with backend/DB verification.
const { test, expect } = require('@playwright/test');
const { USER, collect, appErrors, loginUser } = require('../helpers');

function dialogs(page, answers) {
  page.on('dialog', (d) => (d.type() === 'confirm' ? d.accept() : d.accept(String(answers.shift() ?? ''))));
}
async function openPanel(page, name) {
  await page.evaluate((n) => window.openFeaturePanel(n), name);
  await expect(page.locator(`#fp-${name}`)).toHaveClass(/open/);
  await page.waitForTimeout(500);
}
async function apiGet(page, path) {
  const r = await page.request.get(`${USER}${path}`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

test('Memory — create (textarea) + read + delete round-trip', async ({ page }) => {
  const bag = collect(page);
  dialogs(page, []); // delete confirm
  await loginUser(page);
  const text = 'E2E-Memory-' + Date.now();
  await openPanel(page, 'memory');
  await page.evaluate(() => window.showAddMemory());
  await page.fill('#memoryAddText', text);
  await page.locator('#fp-memory').getByRole('button', { name: 'Save memory' }).click();
  await expect(page.locator('#memoryList').getByText(text, { exact: false })).toBeVisible({ timeout: 8000 });

  let mems = await apiGet(page, '/api/memory/search?q=' + encodeURIComponent('E2E-Memory'));
  let created = (Array.isArray(mems) ? mems : mems.results || []).find((m) => (m.text || '').includes(text));
  expect(created, 'memory persisted in backend').toBeTruthy();

  await page.locator('#memoryList .memory-item', { hasText: text }).locator('.memory-item-del').click();
  await expect(page.locator('#memoryList').getByText(text, { exact: false })).toHaveCount(0, { timeout: 8000 });
  expect(appErrors(bag)).toEqual([]);
});

test('Skills — create (prompts) + read + delete round-trip', async ({ page }) => {
  const bag = collect(page);
  const name = 'E2E-Skill-' + Date.now();
  dialogs(page, [name, 'does e2e things', 'when testing', 'general']);
  await loginUser(page);
  await openPanel(page, 'skills');
  await page.locator('#fp-skills').getByRole('button', { name: '+ Add' }).click();
  await expect(page.locator('#skillsList').getByText(name, { exact: false })).toBeVisible({ timeout: 8000 });

  const skills = await apiGet(page, '/api/skills/search?q=' + encodeURIComponent('E2E-Skill'));
  const created = (Array.isArray(skills) ? skills : skills.results || []).find((s) => s.name === name);
  expect(created, 'skill persisted in backend').toBeTruthy();

  await page.locator('#skillsList .skill-item', { hasText: name }).getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('#skillsList').getByText(name, { exact: false })).toHaveCount(0, { timeout: 8000 });
  expect(appErrors(bag)).toEqual([]);
});

test('Gallery — create album (prompt) + backend verify + API cleanup', async ({ page }) => {
  const bag = collect(page);
  const name = 'E2E-Album-' + Date.now();
  dialogs(page, [name]);
  await loginUser(page);
  await openPanel(page, 'gallery');
  await page.locator('#fp-gallery').getByRole('button', { name: '+ Album' }).click();
  await page.waitForTimeout(1000);

  const albums = await apiGet(page, '/api/gallery/albums');
  const created = (Array.isArray(albums) ? albums : albums.albums || []).find((a) => a.name === name);
  expect(created, 'album persisted in backend').toBeTruthy();
  expect(appErrors(bag)).toEqual([]);

  const del = await page.request.delete(`${USER}/api/gallery/albums/${created.id}`,
    { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
  expect(del.ok()).toBeTruthy();
});

test('Email — add account (prompts) + backend verify + API cleanup (BUG-012 path)', async ({ page }) => {
  const bag = collect(page);
  const email = `e2e.acct${Date.now()}@test.local`;
  // showAddEmailAccount prompts: IMAP host, email(username), password, SMTP host.
  dialogs(page, ['imap.test.local', email, 'app-pass', 'smtp.test.local']);
  await loginUser(page);
  await openPanel(page, 'email');
  await page.locator('#fp-email').getByRole('button', { name: '+ Add account' }).first().click();
  await page.waitForTimeout(1200);

  const accounts = await apiGet(page, '/api/email/accounts');
  const created = (Array.isArray(accounts) ? accounts : accounts.accounts || []).find((a) => a.username === email);
  expect(created, 'email account persisted in backend (no 500)').toBeTruthy();
  // Password must be encrypted — never returned in plaintext.
  expect(JSON.stringify(created)).not.toContain('app-pass');
  expect(appErrors(bag)).toEqual([]);

  const del = await page.request.delete(`${USER}/api/email/accounts/${created.id}`,
    { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
  expect(del.ok()).toBeTruthy();
});
