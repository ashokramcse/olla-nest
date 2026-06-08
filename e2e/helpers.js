// Shared E2E helpers: auth fixtures + diagnostics collector.
const ADMIN = 'http://localhost:8080';
const USER = 'http://localhost:8081';
const ADMIN_EMAIL = 'admin@ollanest.local';
const ADMIN_PASS = 'REDACTED_TEST_CRED';
const QA_EMAIL = 'qa.user@test.local';
const QA_PASS = 'REDACTED_TEST_CRED';

// Attach console-error / pageerror / failed-request collectors to a page.
// Returns the bag; benign favicon/manifest noise is filtered by callers.
function collect(page) {
  const bag = { consoleErrors: [], pageErrors: [], failedRequests: [] };
  page.on('console', (m) => { if (m.type() === 'error') bag.consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => bag.pageErrors.push(String(e)));
  page.on('requestfailed', (r) => bag.failedRequests.push(`${r.method()} ${r.url()} :: ${r.failure()?.errorText}`));
  return bag;
}

function appErrors(bag) {
  return bag.consoleErrors.filter((e) => !/favicon|manifest|the server responded with a status of 404/i.test(e));
}

async function horizontalOverflow(page) {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

async function loginAdmin(page) {
  await page.goto(ADMIN + '/admin-login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', ADMIN_EMAIL);
  await page.fill('#password', ADMIN_PASS);
  await page.click('#submitBtn');
  await page.waitForURL(/\/admin(\b|$|\?)/, { timeout: 15000 });
}

async function loginUser(page) {
  await page.goto(USER + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#email', QA_EMAIL);
  await page.fill('#password', QA_PASS);
  await page.click('#submitBtn');
  await page.waitForURL(/\/app(\b|$|\?)/, { timeout: 15000 });
}

module.exports = {
  ADMIN, USER, ADMIN_EMAIL, ADMIN_PASS, QA_EMAIL, QA_PASS,
  collect, appErrors, horizontalOverflow, loginAdmin, loginUser,
};
