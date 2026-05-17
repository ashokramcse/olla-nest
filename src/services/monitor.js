/**
 * @file src/services/monitor.js
 * @description Lightweight in-process metrics collector — no external dependencies.
 * Tracks request counts, chat activity, Ollama sync results, and DB query counts.
 */

const os = require('os');

const startTime = Date.now();

const metrics = {
  requests: { total: 0, errors: 0 },
  chat: { total: 0, streaming: 0, failed: 0, tokensTotal: 0 },
  ollama: { syncs: 0, failures: 0 },
  db: { queries: 0 },
};

/**
 * Increment a counter at a dot-separated path, e.g. inc('requests.total').
 * @param {string} dotPath
 * @param {number} [amount=1]
 */
function inc(dotPath, amount = 1) {
  const parts = dotPath.split('.');
  let obj = metrics;
  for (let i = 0; i < parts.length - 1; i++) {
    if (!obj[parts[i]]) obj[parts[i]] = {};
    obj = obj[parts[i]];
  }
  const key = parts[parts.length - 1];
  obj[key] = (obj[key] || 0) + amount;
}

function formatUptime(ms) {
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  const h = Math.floor(m / 60);
  const d = Math.floor(h / 24);
  if (d > 0) return `${d}d ${h % 24}h`;
  if (h > 0) return `${h}h ${m % 60}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

function getSnapshot() {
  return {
    uptime: Math.floor((Date.now() - startTime) / 1000),
    uptimeHuman: formatUptime(Date.now() - startTime),
    memory: process.memoryUsage(),
    cpu: os.loadavg(),
    platform: process.platform,
    nodeVersion: process.version,
    pid: process.pid,
    metrics,
  };
}

module.exports = { inc, getSnapshot };
