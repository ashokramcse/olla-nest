// UI CRUD round-trip for scheduled Tasks (create via prompts, delete via confirm),
// with backend/DB persistence verification. Also guards BUG-009 at the UI level:
// creating tasks must not 500 on a unique-id collision.
const { test, expect } = require('@playwright/test');
const { USER, collect, appErrors, loginUser } = require('../helpers');

async function openTasks(page) {
  await page.evaluate(() => window.openFeaturePanel('tasks'));
  await expect(page.locator('#fp-tasks')).toHaveClass(/open/);
  await page.waitForTimeout(600);
}

async function apiTasks(page) {
  const r = await page.request.get(`${USER}/api/tasks`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

test('Tasks — create + read + delete round-trip via UI + DB verification', async ({ page }) => {
  const bag = collect(page);
  const answers = [];
  page.on('dialog', async (d) => {
    if (d.type() === 'confirm') return d.accept();
    const a = answers.shift();
    return a === undefined ? d.dismiss() : d.accept(String(a));
  });

  await loginUser(page);
  const name = 'E2E-Task-' + Date.now();

  // ── CREATE (prompts: name, prompt, time) ─────────────────────────────────
  answers.push(name, 'do the e2e thing', '09:30');
  await openTasks(page);
  await page.locator('#fp-tasks').getByRole('button', { name: '+ Task' }).click();
  await expect(page.locator('#tasksList').getByText(name, { exact: false })).toBeVisible({ timeout: 8000 });

  // DB: task persisted with our name + scheduled_time.
  let tasks = await apiTasks(page);
  const created = tasks.find((t) => t.name === name);
  expect(created, 'task persisted in backend').toBeTruthy();
  const id = created.id;
  expect(String(created.scheduled_time)).toContain('09:30');
  expect(appErrors(bag), `console errors (create): ${appErrors(bag).join(' | ')}`).toEqual([]);

  // ── DELETE (Delete button -> confirm) ────────────────────────────────────
  // The task delete control is the danger "×" button on the task row.
  await page.locator('#tasksList .task-item', { hasText: name })
    .locator('.fp-btn.danger').click();
  await expect(page.locator('#tasksList').getByText(name, { exact: false })).toHaveCount(0, { timeout: 8000 });

  tasks = await apiTasks(page);
  expect(tasks.find((t) => t.id === id), 'task removed from backend').toBeFalsy();
  expect(appErrors(bag), `console errors: ${appErrors(bag).join(' | ')}`).toEqual([]);
});
