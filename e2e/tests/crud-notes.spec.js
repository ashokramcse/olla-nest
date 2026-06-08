// Full UI CRUD round-trip for Notes, driven through the real UI (which uses
// native prompt()/confirm() dialogs), with backend/DB persistence verification.
const { test, expect } = require('@playwright/test');
const { USER, collect, appErrors, loginUser } = require('../helpers');

async function openNotes(page) {
  await page.evaluate(() => window.openFeaturePanel('notes'));
  await expect(page.locator('#fp-notes')).toHaveClass(/open/);
  await page.waitForTimeout(600);
}

// List the user's notes via the authenticated API (shares the page's cookies).
async function apiNotes(page) {
  const r = await page.request.get(`${USER}/api/notes`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

test('Notes — full CRUD round-trip via UI + DB verification', async ({ page }) => {
  const bag = collect(page);

  // Single dialog handler backed by a mutable answer queue (refilled per action).
  // prompt() -> next queued answer; confirm() -> accept.
  const answers = [];
  page.on('dialog', async (d) => {
    if (d.type() === 'confirm') return d.accept();
    const a = answers.shift();
    return a === undefined ? d.dismiss() : d.accept(String(a));
  });

  await loginUser(page);
  const uniq = 'E2E-Note-' + Date.now();
  const editedTitle = uniq + '-EDITED';

  // ── CREATE (prompts: title, content) ─────────────────────────────────────
  answers.push(uniq, 'created by e2e');
  await openNotes(page);
  await page.locator('#fp-notes').getByRole('button', { name: '+ Note' }).click();
  await expect(page.locator('#notesList').getByText(uniq, { exact: false })).toBeVisible({ timeout: 8000 });

  // DB/persistence: the note exists in the backend with our title + content.
  let notes = await apiNotes(page);
  const created = notes.find((n) => n.title === uniq);
  expect(created, 'note persisted in backend').toBeTruthy();
  const id = created.id;
  expect(created.content).toBe('created by e2e');
  // No console errors during the CREATE phase.
  expect(appErrors(bag), `console errors (create): ${appErrors(bag).join(' | ')}`).toEqual([]);

  // ── READ across full page reload (true persistence) ──────────────────────
  await page.reload({ waitUntil: 'domcontentloaded' });
  // Reload aborts any in-flight requests (net::ERR_ABORTED/ERR_FAILED) — expected
  // navigation noise, not an app error. Reset the bag so the post-reload phases
  // are asserted cleanly.
  bag.consoleErrors.length = 0;
  await openNotes(page);
  await expect(page.locator('#notesList').getByText(uniq, { exact: false })).toBeVisible({ timeout: 8000 });

  // ── UPDATE (click card -> edit prompts: new title, new content) ───────────
  answers.push(editedTitle, 'edited by e2e');
  await page.locator('#notesList .note-card', { hasText: uniq }).first().click();
  await expect(page.locator('#notesList').getByText(editedTitle, { exact: false })).toBeVisible({ timeout: 8000 });
  notes = await apiNotes(page);
  const updated = notes.find((n) => n.id === id);
  expect(updated.title, 'title updated in backend').toBe(editedTitle);
  expect(updated.content).toBe('edited by e2e');

  // ── DELETE (Delete button -> confirm) ────────────────────────────────────
  await page.locator('#notesList .note-card', { hasText: editedTitle })
    .getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('#notesList').getByText(editedTitle, { exact: false })).toHaveCount(0, { timeout: 8000 });

  // DB: note is gone from the backend.
  notes = await apiNotes(page);
  expect(notes.find((n) => n.id === id), 'note removed from backend').toBeFalsy();

  // No app console errors during the READ/UPDATE/DELETE phases (post-reload).
  expect(appErrors(bag), `console errors: ${appErrors(bag).join(' | ')}`).toEqual([]);
});
