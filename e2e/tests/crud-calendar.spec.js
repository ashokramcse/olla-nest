// Calendar create round-trip: create an event via the real UI (+ Event prompts),
// verify it persisted in the backend, and verify a day-cell event dot renders.
// NOTE: the month-grid renders events only as anonymous dots with no per-event
// edit/delete control (UX/functionality gap — see UI_UX_AUDIT_REPORT OBS-003),
// so cleanup is done via the API.
const { test, expect } = require('@playwright/test');
const { USER, collect, appErrors, loginUser } = require('../helpers');

async function openCalendar(page) {
  await page.evaluate(() => window.openFeaturePanel('calendar'));
  await expect(page.locator('#fp-calendar')).toHaveClass(/open/);
  await page.waitForTimeout(700);
}

test('Calendar — create event via UI + DB verification', async ({ page }) => {
  const bag = collect(page);
  const answers = [];
  page.on('dialog', async (d) => {
    if (d.type() === 'confirm') return d.accept();
    const a = answers.shift();
    return a === undefined ? d.dismiss() : d.accept(String(a));
  });

  await loginUser(page);
  const title = 'E2E-Event-' + Date.now();

  // ── CREATE (prompts: title, time) ────────────────────────────────────────
  answers.push(title, '10:00');
  await openCalendar(page);
  await page.locator('#fp-calendar').getByRole('button', { name: '+ Event' }).click();
  await page.waitForTimeout(1200);

  // DB: the event persisted this month with our title.
  const now = new Date();
  const from = new Date(now.getFullYear(), now.getMonth(), 1).toISOString();
  const to = new Date(now.getFullYear(), now.getMonth() + 1, 1).toISOString();
  const r = await page.request.get(`${USER}/api/calendar/events?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  expect(r.ok()).toBeTruthy();
  const events = await r.json();
  const created = events.find((e) => e.title === title);
  expect(created, 'event persisted in backend').toBeTruthy();

  // UI: at least one event dot is rendered on the grid.
  await expect(page.locator('#fp-calendar .cal-event-dot').first()).toBeVisible({ timeout: 6000 });
  expect(appErrors(bag), `console errors: ${appErrors(bag).join(' | ')}`).toEqual([]);

  // Cleanup via API (no UI delete control on the month grid).
  const del = await page.request.delete(`${USER}/api/calendar/events/${created.id}`, {
    headers: { 'X-Requested-With': 'XMLHttpRequest' },
  });
  expect(del.ok()).toBeTruthy();
});
