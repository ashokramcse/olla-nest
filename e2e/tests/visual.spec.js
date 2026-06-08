// Visual-regression baselines for the static login pages at multiple viewports.
// First run (npx playwright test visual.spec.js --update-snapshots) creates the
// baselines; later runs pixel-compare against them to catch unintended CSS/layout
// changes. Scoped to the login pages because they are deterministic (no live
// data, timestamps, or async content). Snapshots are OS/engine-specific
// (Chromium on the recording platform).
const { test, expect } = require('@playwright/test');

const VIEWPORTS = [
  { w: 375, h: 812, label: 'mobile' },
  { w: 768, h: 1024, label: 'tablet' },
  { w: 1440, h: 900, label: 'desktop' },
];

for (const [name, url] of [['admin-login', 'http://localhost:8080/admin-login'], ['user-login', 'http://localhost:8081/login']]) {
  for (const vp of VIEWPORTS) {
    test(`visual: ${name} @ ${vp.label} (${vp.w})`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h });
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      // Settle fonts/layout before snapshotting.
      await page.evaluate(() => document.fonts && document.fonts.ready);
      await page.waitForTimeout(800);
      await expect(page).toHaveScreenshot(`${name}-${vp.label}.png`, {
        fullPage: true,
        maxDiffPixelRatio: 0.02, // tolerate sub-2% antialiasing/render noise
        animations: 'disabled',
      });
    });
  }
}
