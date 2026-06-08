// Authenticated deep-journey E2E across every employee-workspace feature panel.
// Gates per panel: panel opens (#fp-<name>.open visible), no app console errors,
// no horizontal overflow, and Escape closes it (modal a11y).
const { test, expect } = require('@playwright/test');
const { collect, appErrors, horizontalOverflow, loginUser } = require('../helpers');

// data-panel values from public/app.html.
const PANELS = ['memory', 'skills', 'notes', 'tasks', 'email', 'calendar', 'compare',
  'cookbook', 'assistant', 'research', 'contacts', 'gallery', 'presets'];

test.describe('Workspace — authenticated panel journeys', () => {
  test('app shell loads after non-admin login', async ({ page }) => {
    const bag = collect(page);
    await loginUser(page);
    // Feature nav items exist in the DOM (launcher for the panels).
    const count = await page.locator('.feature-nav-item').count();
    expect(count, 'feature nav items present').toBeGreaterThan(0);
    await page.screenshot({ path: 'evidence/user-shell.png', fullPage: true });
    expect(bag.pageErrors).toEqual([]);
  });

  for (const panel of PANELS) {
    test(`panel "${panel}" opens, renders, no console errors, Escape closes`, async ({ page }) => {
      const bag = collect(page);
      await loginUser(page);
      // The panel container must exist in the DOM.
      await expect(page.locator(`#fp-${panel}`)).toHaveCount(1);
      // Open via the real handler (nav items may live in a launcher menu).
      await page.evaluate((n) => window.openFeaturePanel(n), panel);
      const view = page.locator(`#fp-${panel}`);
      await expect(view).toBeVisible({ timeout: 8000 });
      await expect(view).toHaveClass(/open/);
      // Let any data fetch render its state (list / empty / error).
      await page.waitForTimeout(1000);
      await page.screenshot({ path: `evidence/user-panel-${panel}.png` });
      // No uncaught JS errors and no app console errors while the panel is open.
      expect(bag.pageErrors, `pageErrors on ${panel}: ${bag.pageErrors.join(' | ')}`).toEqual([]);
      expect(appErrors(bag), `console errors on ${panel}: ${appErrors(bag).join(' | ')}`).toEqual([]);
      // No broken responsive layout.
      const o = await horizontalOverflow(page);
      expect(o, `horizontal overflow ${o}px on ${panel}`).toBeLessThanOrEqual(2);
      // Panel has rendered some content (header/controls/empty-state text).
      const text = (await view.innerText()).trim();
      expect(text.length, `panel ${panel} rendered content`).toBeGreaterThan(0);
      // Accessibility: Escape closes the modal.
      await page.keyboard.press('Escape');
      await expect(view).not.toHaveClass(/open/, { timeout: 4000 });
    });
  }
});
