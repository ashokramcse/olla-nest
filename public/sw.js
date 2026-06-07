/**
 * Olla Nest Service Worker — v2026.2.1
 * Minimal offline shell: caches the app shell and serves it ONLY when the
 * network is unavailable. Uses a network-first strategy so fresh HTML/JS/CSS is
 * always delivered when online — a previous cache-first version could serve a
 * stale shell (old JS) even after a hard refresh, breaking redirects after
 * deploys. Does NOT cache API responses (data must always be fresh).
 */

const CACHE = 'olla-nest-shell-v2026.2.1';
const SHELL = [
  '/',
  '/app',
  '/login',
  '/styles.css',
  '/theme.js',
  '/app.js',
  '/features.js',
  '/dropdown.js',
  '/favicon.svg',
  '/vendor/marked.min.js',
  '/vendor/purify.min.js',
  '/vendor/highlight.min.js',
  '/vendor/highlight-github.min.css',
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  // Skip API calls — always network
  if (e.request.url.includes('/api/')) return;
  // Skip non-GET
  if (e.request.method !== 'GET') return;

  // Network-first: always try the network so fresh HTML/JS/CSS wins. Fall back to
  // the cached shell only when the network fails (offline). Successful responses
  // refresh the cache for the next offline visit.
  e.respondWith(
    fetch(e.request).then(res => {
      if (res.ok && res.status < 400) {
        const clone = res.clone();
        caches.open(CACHE).then(c => c.put(e.request, clone));
      }
      return res;
    }).catch(() => caches.match(e.request))
  );
});
