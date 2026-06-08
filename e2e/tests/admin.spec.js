// Authenticated deep-journey E2E across every admin tab.
// Gates per tab: tab view becomes visible, no app console errors, no horizontal overflow.
const { test, expect } = require('@playwright/test');
const { collect, appErrors, horizontalOverflow, loginAdmin } = require('../helpers');

// data-tab values from public/admin.html (excludes hidden "audit").
const TABS = ['overview', 'models', 'users', 'access', 'settings', 'providers', 'connectors', 'sso', 'reports'];

test.describe('Admin panel — authenticated deep journeys', () => {
  test('dashboard loads with key metric cards', async ({ page }) => {
    const bag = collect(page);
    await loginAdmin(page);
    await expect(page.locator('text=ADMIN PANEL').first()).toBeVisible();
    // Overview must show the metric labels
    for (const label of ['MODELS', 'USERS', 'DEPARTMENTS', 'GROUPS']) {
      await expect(page.locator(`text=${label}`).first()).toBeVisible();
    }
    await page.screenshot({ path: 'evidence/admin-overview.png', fullPage: true });
    expect(bag.pageErrors).toEqual([]);
  });

  for (const tab of TABS) {
    test(`tab "${tab}" opens, renders, no console errors, no overflow`, async ({ page }) => {
      const bag = collect(page);
      await loginAdmin(page);
      const navBtn = page.locator(`.nav-item[data-tab="${tab}"]`);
      await expect(navBtn).toBeVisible();
      await navBtn.click();
      // The matching tab-view becomes the active/visible view.
      const view = page.locator(`#tab-${tab}`);
      await expect(view).toBeVisible({ timeout: 10000 });
      // Give async data fetches a moment to resolve/render their state.
      await page.waitForTimeout(1200);
      await page.screenshot({ path: `evidence/admin-tab-${tab}.png`, fullPage: true });
      // No uncaught JS errors on the page.
      expect(bag.pageErrors, `pageErrors on ${tab}: ${bag.pageErrors.join(' | ')}`).toEqual([]);
      // No app-level console errors (favicon/404-dev-hints noise filtered).
      expect(appErrors(bag), `console errors on ${tab}: ${appErrors(bag).join(' | ')}`).toEqual([]);
      // No broken responsive layout at default desktop width.
      const o = await horizontalOverflow(page);
      expect(o, `horizontal overflow ${o}px on ${tab}`).toBeLessThanOrEqual(2);
      // The view is not empty (has some rendered text/controls).
      const text = (await view.innerText()).trim();
      expect(text.length, `tab ${tab} rendered some content`).toBeGreaterThan(0);
    });
  }

  test('Users tab lists the seeded users (data loads from API)', async ({ page }) => {
    await loginAdmin(page);
    await page.locator('.nav-item[data-tab="users"]').click();
    await expect(page.locator('#tab-users')).toBeVisible();
    await page.waitForTimeout(1500);
    // The admin account must appear in the users list.
    await expect(page.locator('text=admin@ollanest.local').first()).toBeVisible({ timeout: 10000 });
  });
});
