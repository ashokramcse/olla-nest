// Shared E2E helpers: auth fixtures + diagnostics collector.
const ADMIN = 'http://localhost:8080';
const USER = 'http://localhost:8081';
// Credentials are NEVER committed as literals (GitGuardian-flagged). Resolve from
// env vars first, then a gitignored ./.e2e-creds.js local override; fail loudly if
// neither is present so a misconfigured run can't silently use a baked-in secret.
let _localCreds = {};
try { _localCreds = require('./.e2e-creds.js'); } catch (_) { /* env-only mode */ }
function cred(name) {
    const v = process.env[name] || _localCreds[name];
    if (!v) throw new Error(`Missing E2E credential ${name}: set env ${name} or copy e2e/.e2e-creds.example.js -> e2e/.e2e-creds.js`);
    return v;
}
const ADMIN_EMAIL = cred('ADMIN_EMAIL');
const ADMIN_PASS = cred('ADMIN_PASS');
const QA_EMAIL = cred('QA_EMAIL');
const QA_PASS = cred('QA_PASS');

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
    // Filter benign/environmental noise that is not an application defect:
    // - favicon/manifest 404s
    // - Google Fonts (fonts.gstatic.com) download failures when the test sandbox
    //   has no external network — Firefox logs these as JS errors; the app falls
    //   back to system fonts (tracked as OBS-004, external font-CDN dependency).
    return bag.consoleErrors.filter(
        (e) => !/favicon|manifest|the server responded with a status of 404|downloadable font|fonts\.gstatic\.com|fonts\.googleapis\.com/i.test(e));
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
