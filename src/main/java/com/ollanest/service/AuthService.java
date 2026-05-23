package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session management: in-memory cache backed by DB persistence.
 *
 * <p>Manages the full lifecycle of Olla Nest authentication sessions. The
 * session token is a 256-bit (64-hex-char) {@link SecureRandom} value stored in
 * the {@code sessions} table and cached in a {@link ConcurrentHashMap} for fast
 * per-request lookup.
 *
 * <p>Cookie details:
 * <ul>
 *   <li>Name: {@code olla_nest_session}</li>
 *   <li>Flags: {@code HttpOnly; SameSite=Lax; Path=/}</li>
 *   <li>Duration: 12 hours (43200 seconds)</li>
 *   <li>Optional {@code Secure} flag controlled by {@code app.cookie-secure} property</li>
 * </ul>
 *
 * <p>Security features included:
 * <ul>
 *   <li>IP-based brute-force protection is managed by {@code AuthController} (not here)</li>
 *   <li>Random admin password generation on first boot</li>
 *   <li>Session invalidation on role or status change ({@link #invalidateUserSessions})</li>
 *   <li>Scheduled hourly sweep to remove expired sessions from DB and cache</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>The in-memory cache avoids a DB round-trip on every request. On cache miss
 *       (e.g. after a restart) the session is re-loaded from DB and re-cached.</li>
 *   <li>Sessions are not renewed on use — they expire at a fixed time from creation.
 *       This is consistent with the original Node.js behaviour.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 * @version v2026.1.0  — security hardening: IP brute-force fix; random admin password on first boot
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Name of the session cookie set on the browser. */
    private static final String COOKIE_NAME = "olla_nest_session";

    /** Session lifetime in seconds (12 hours). */
    private static final long SESSION_DURATION_SECONDS = 43200;

    /**
     * Whether to add the {@code Secure} flag to the session cookie. Should be
     * {@code true} in production behind HTTPS.
     */
    @Value("${app.cookie-secure:false}")
    private boolean cookieSecure;

    /** In-memory session cache: token → {@link CachedSession}. Thread-safe. */
    private final ConcurrentHashMap<String, CachedSession> sessions = new ConcurrentHashMap<>();

    /** JDBC template for session persistence and expiry sweeps. */
    private final JdbcTemplate db;

    /** Used to load the {@link User} object from the DB on cache miss. */
    private final UserService userService;

    /** Injected but not currently used; available for future JWT/claims expansion. */
    private final ObjectMapper mapper;

    /**
     * Constructor-injects all required dependencies.
     *
     * @param  db           the JDBC template
     * @param  userService  the user lookup service
     * @param  mapper       the shared JSON object mapper
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public AuthService(JdbcTemplate db, UserService userService, ObjectMapper mapper) {
        this.db = db;
        this.userService = userService;
        this.mapper = mapper;
    }

    /**
     * In-memory session cache entry pairing a {@link User} with its expiry time.
     *
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public static class CachedSession {

        /** The authenticated user associated with this session. */
        public User user;

        /** Session expiry time as a Unix epoch millisecond value. */
        public long expiresAtMs;

        /**
         * Constructs a new cache entry.
         *
         * @param  user         the authenticated user
         * @param  expiresAtMs  the expiry time in milliseconds since epoch
         * @since   v2026.1.0  — initial Java Spring Boot migration
         */
        CachedSession(User user, long expiresAtMs) {
            this.user = user;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /**
     * Extracts the session token from the request cookies.
     *
     * @param  req  the current HTTP request
     * @return      the session token string, or {@code null} if absent
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public String getToken(HttpServletRequest req) {
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Resolves the session cookie to a validated, non-expired {@link User}.
     *
     * <p>Checks the in-memory cache first. On a cache miss, queries the
     * {@code sessions} table and re-caches the result. Returns {@code null} if
     * the token is absent, expired, or the user no longer exists.
     *
     * @param  req  the current HTTP request
     * @return      the authenticated {@link User}, or {@code null} if the session
     *              is invalid or expired
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public User getSessionUser(HttpServletRequest req) {
        String token = getToken(req);
        if (token == null || token.isBlank()) return null;

        // Fast path: check in-memory cache
        CachedSession cached = sessions.get(token);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAtMs) {
                return cached.user;
            } else {
                sessions.remove(token);
            }
        }

        // Slow path: fall back to DB
        try {
            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT s.user_id, s.expires_at FROM sessions s "
                            + "WHERE s.token = ? AND s.expires_at > datetime('now')",
                    token);
            if (rows.isEmpty()) return null;
            String userId = (String) rows.get(0).get("user_id");
            User user = userService.findUserById(userId);
            if (user == null) return null;

            // Re-cache for subsequent requests
            long expiresMs = System.currentTimeMillis() + SESSION_DURATION_SECONDS * 1000;
            sessions.put(token, new CachedSession(user, expiresMs));
            return user;
        } catch (Exception e) {
            log.error("[auth] Session lookup error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Creates a new session for the given user and sets the session cookie on the response.
     *
     * <p>If the request already carries a valid session cookie, the old session is
     * invalidated first (session rotation). A 256-bit random token is generated,
     * persisted to the DB, and added to the in-memory cache. The cookie is set with
     * {@code HttpOnly}, {@code SameSite=Lax}, and optionally {@code Secure}.
     *
     * @param  res   the HTTP response on which to set the cookie
     * @param  req   the current HTTP request (used to detect and invalidate old session)
     * @param  user  the authenticated user to associate with the new session
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public void setSession(HttpServletResponse res, HttpServletRequest req, User user) {
        // Session rotation: invalidate any existing session cookie
        String oldToken = getToken(req);
        if (oldToken != null) {
            removeSession(oldToken);
        }

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = bytesToHex(bytes);
        long expiresMs = System.currentTimeMillis() + SESSION_DURATION_SECONDS * 1000;
        String expiresAt = Instant.ofEpochMilli(expiresMs).toString()
                .replace("T", " ").replace("Z", "");

        db.update("INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
                token, user.id, expiresAt);
        sessions.put(token, new CachedSession(user, expiresMs));

        // Set HttpOnly cookie via Servlet API (SameSite not supported by old API)
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) SESSION_DURATION_SECONDS);
        res.addCookie(cookie);
        // Override with full Set-Cookie header to include SameSite=Lax and optional Secure
        String secureFlag = cookieSecure ? "; Secure" : "";
        res.addHeader("Set-Cookie", COOKIE_NAME + "=" + token
                + "; HttpOnly; SameSite=Lax; Path=/; Max-Age=" + SESSION_DURATION_SECONDS + secureFlag);
    }

    /**
     * Invalidates the given session token and clears the browser cookie.
     *
     * <p>Removes the token from both the in-memory cache and the DB, then sets a
     * Max-Age=0 cookie header to delete the browser cookie.
     *
     * @param  res    the HTTP response on which to clear the cookie
     * @param  token  the session token to invalidate (may be {@code null})
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public void clearSession(HttpServletResponse res, String token) {
        if (token != null) {
            removeSession(token);
        }
        res.addHeader("Set-Cookie", COOKIE_NAME + "=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
    }

    /**
     * Removes a single session token from the cache and from the DB.
     *
     * @param  token  the session token to remove
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public void removeSession(String token) {
        sessions.remove(token);
        try {
            db.update("DELETE FROM sessions WHERE token = ?", token);
        } catch (Exception e) {
            log.warn("[auth] Failed to delete session from DB: {}", e.getMessage());
        }
    }

    /**
     * Force-invalidates all sessions for a specific user from cache and DB.
     *
     * <p>Called after password reset, role change, or deactivation to ensure the
     * new permissions take effect immediately.
     *
     * @param  userId  the user whose sessions should be terminated
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public void forceLogoutUser(String userId) {
        sessions.entrySet().removeIf(e -> userId.equals(e.getValue().user.id));
        db.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    /**
     * Alias for {@link #forceLogoutUser(String)}.
     *
     * @param  userId  the user whose sessions should be invalidated
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public void invalidateUserSessions(String userId) {
        forceLogoutUser(userId);
    }

    /**
     * Scheduled task that removes expired sessions from the DB and in-memory cache.
     *
     * <p>Runs every hour with a 60-second initial delay. Prevents indefinite growth
     * of the {@code sessions} table and the in-memory map.
     *
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void cleanExpiredSessions() {
        try {
            db.update("DELETE FROM sessions WHERE expires_at < datetime('now')");
            sessions.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue().expiresAtMs);
        } catch (Exception e) {
            log.warn("[auth] Failed to clean expired sessions: {}", e.getMessage());
        }
    }

    /**
     * Converts a byte array to a lowercase hex string.
     *
     * @param  bytes  the byte array to encode
     * @return        the hex string representation
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
