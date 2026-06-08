// Full UI CRUD round-trip for Contacts (create via prompts, delete via confirm),
// with backend/DB persistence verification.
const { test, expect } = require('@playwright/test');
const { USER, collect, appErrors, loginUser } = require('../helpers');

async function openContacts(page) {
  await page.evaluate(() => window.openFeaturePanel('contacts'));
  await expect(page.locator('#fp-contacts')).toHaveClass(/open/);
  await page.waitForTimeout(600);
}

async function apiContacts(page) {
  const r = await page.request.get(`${USER}/api/contacts?limit=200`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

test('Contacts — full CRUD round-trip via UI + DB verification', async ({ page }) => {
  const bag = collect(page);
  const answers = [];
  page.on('dialog', async (d) => {
    if (d.type() === 'confirm') return d.accept();
    const a = answers.shift();
    return a === undefined ? d.dismiss() : d.accept(String(a));
  });

  await loginUser(page);
  const name = 'E2E Contact ' + Date.now();
  const email = `e2e${Date.now()}@test.local`;

  // ── CREATE (prompts: name, email, org) ───────────────────────────────────
  answers.push(name, email, 'E2E Org');
  await openContacts(page);
  await page.locator('#fp-contacts').getByRole('button', { name: '+ Contact' }).click();
  await expect(page.locator('#contactsList').getByText(name, { exact: false })).toBeVisible({ timeout: 8000 });

  // DB: contact persisted with our display_name + organization.
  let contacts = await apiContacts(page);
  const created = contacts.find((c) => c.display_name === name);
  expect(created, 'contact persisted in backend').toBeTruthy();
  const id = created.id;
  expect(created.organization).toBe('E2E Org');
  expect(appErrors(bag), `console errors (create): ${appErrors(bag).join(' | ')}`).toEqual([]);

  // ── DELETE (× button -> confirm) ─────────────────────────────────────────
  await page.locator('#contactsList .memory-item', { hasText: name })
    .locator('.memory-item-del').click();
  await expect(page.locator('#contactsList').getByText(name, { exact: false })).toHaveCount(0, { timeout: 8000 });

  contacts = await apiContacts(page);
  expect(contacts.find((c) => c.id === id), 'contact removed from backend').toBeFalsy();
  expect(appErrors(bag), `console errors: ${appErrors(bag).join(' | ')}`).toEqual([]);
});
