/**
 * Olla Nest Service Worker — v2026.2.0
 * Minimal offline shell: caches app shell, serves cached UI on network failure.
 * Does NOT cache API responses (data must always be fresh).
 */

const CACHE = 'olla-nest-shell-v2026.2.0';
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

  e.respondWith(
    caches.match(e.request).then(cached => {
      const network = fetch(e.request).then(res => {
        if (res.ok && res.status < 400) {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      }).catch(() => null);
      return cached || network;
    })
  );
});
