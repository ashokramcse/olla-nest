/**
 * @file src/middleware/security.js
 * @description Security headers middleware, loginLimiter, checkChatRateLimit, enforceDockerRuntime.
 */

const fs = require("fs");
const { LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS } = require("../config");

// In-memory login rate limiter: track failed attempts per IP
const loginAttempts = new Map(); // ip -> { count, resetAt }

// Per-user chat rate limiter (sliding window — timestamps per user)
const chatRateLimiter = new Map(); // userId -> [timestamp, ...]

/**
 * Checks whether a user is within their per-minute chat rate limit.
 *
 * Uses a sliding-window algorithm: keep only timestamps that fall within the
 * last 60 seconds, then compare the count against the user's configured limit.
 *
 * @param {string} userId - The authenticated user's ID.
 * @param {number} limitPerMinute - The user's api_rate_limit_per_minute setting.
 *   A value of 0 or falsy means unlimited.
 * @returns {boolean} true if the request is allowed, false if the limit is exceeded.
 */
function checkChatRateLimit(userId, limitPerMinute) {
  if (!limitPerMinute || limitPerMinute <= 0) return true; // unlimited
  const now = Date.now();
  const windowStart = now - 60_000;
  // 1. Discard timestamps older than the 60-second window
  const times = (chatRateLimiter.get(userId) || []).filter(t => t > windowStart);
  // 2. Reject if already at or over the limit
  if (times.length >= limitPerMinute) return false;
  // 3. Record this request and persist the updated window
  times.push(now);
  chatRateLimiter.set(userId, times);
  return true;
}

// Clean up old rate limiter entries every 5 minutes
setInterval(() => {
  const cutoff = Date.now() - 60_000;
  for (const [uid, times] of chatRateLimiter) {
    const fresh = times.filter(t => t > cutoff);
    if (fresh.length === 0) chatRateLimiter.delete(uid);
    else chatRateLimiter.set(uid, fresh);
  }
}, 5 * 60 * 1000).unref();

/**
 * Guards against running Olla Nest outside Docker.
 *
 * Olla Nest depends on Docker networking (host.docker.internal) and assumes
 * the /data volume is mounted.  Running it bare on the host will silently
 * break Ollama connectivity and may write data to unexpected paths.
 *
 * The check is bypassed by setting ALLOW_NON_DOCKER=1 for diagnostic runs only.
 */
function enforceDockerRuntime() {
  const inDocker = fs.existsSync("/.dockerenv") || process.env.OLLA_NEST_DOCKER_RUNTIME === "true";
  if (!inDocker && process.env.ALLOW_NON_DOCKER !== "1") {
    console.error("Olla Nest is Docker-only. Start it with: docker compose up --build");
    console.error("For one-off diagnostics only, set ALLOW_NON_DOCKER=1.");
    process.exit(1);
  }
}

/**
 * Express middleware that sets security headers on every response.
 */
function securityHeaders(req, res, next) {
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-XSS-Protection", "1; mode=block");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.setHeader("Content-Security-Policy",
    "default-src 'self'; script-src 'self' 'unsafe-inline' cdn.jsdelivr.net cdnjs.cloudflare.com; style-src 'self' 'unsafe-inline' fonts.googleapis.com cdnjs.cloudflare.com; font-src 'self' fonts.gstatic.com; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'none';"
  );
  // HSTS — only send over HTTPS connections
  if (req.headers["x-forwarded-proto"] === "https" || req.secure) {
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
  next();
}

module.exports = { loginAttempts, chatRateLimiter, checkChatRateLimit, enforceDockerRuntime, securityHeaders, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS };
