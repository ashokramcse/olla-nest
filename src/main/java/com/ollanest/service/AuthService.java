package com.ollanest.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;

/**
 * Session management: in-memory cache backed by DB persistence.
 *
 * <h3>Why this class exists</h3>
 * <p>Manages the full lifecycle of Olla Nest authentication sessions. The session
 *    token is a 256-bit (64-hex-char) {@link SecureRandom} value stored in the
 *    {@code sessions} table and cached in a {@link ConcurrentHashMap} for fast
 *    per-request lookup. This class is the single authority for reading, creating,
 *    and destroying session state — no other class should write to the
 *    {@code sessions} table directly.
 *
 * <h3>Design notes</h3>
 * <p>Cookie configuration:
 * <ul>
 *   <li>Name: {@code olla_nest_session}</li>
 *   <li>Flags: {@code HttpOnly; SameSite=Lax; Path=/}</li>
 *   <li>Duration: 12 hours (43 200 seconds)</li>
 *   <li>Optional {@code Secure} flag controlled by the {@code app.cookie-secure} property</li>
 * </ul>
 *
 * <p>Security features:
 * <ul>
 *   <li>IP-based brute-force protection is handled by {@code AuthController} (not here)</li>
 *   <li>Session rotation on login — the old token is invalidated before a new one is issued</li>
 *   <li>Force-logout support for immediate revocation after a role or status change</li>
 *   <li>Scheduled hourly sweep removes expired rows from {@code sessions} and from the cache</li>
 * </ul>
 *
 * <p>The in-memory cache avoids a DB round-trip on every request. On a cache miss
 *    (e.g., after a server restart) the session is re-loaded from the DB and re-cached.
 *    Sessions are not renewed on use — they expire at a fixed time from creation, consistent
 *    with the original Node.js behaviour.
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 *   <li><b>v2026.1.4</b> — ok:false error responses standardised across auth paths</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Service
public class AuthService {

    /** SLF4J logger for this service. */
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Name of the session cookie set on the browser. */
    private static final String COOKIE_NAME = "olla_nest_session";

    /** Session lifetime in seconds (12 hours). */
    private static final long SESSION_DURATION_SECONDS = 43200;

    /**
     * Whether to append the {@code Secure} flag to the session cookie.
     * Should be {@code true} in production behind HTTPS; defaults to {@code false}
     * so local development works without TLS.
     */
    @Value("${app.cookie-secure:false}")
    private boolean cookieSecure;

    /** In-memory session cache: token → {@link CachedSession}. Thread-safe. */
    private final ConcurrentHashMap<String, CachedSession> sessions = new ConcurrentHashMap<>();

    /** JDBC template used for session persistence and expiry sweeps. */
    private final JdbcTemplate db;

    /** Loads the {@link User} object from the DB on a cache miss. */
    private final UserService userService;

    /** Shared JSON mapper; injected for potential future JWT/claims expansion. */
    private final ObjectMapper mapper;

    /**
     * Constructor-injects all required dependencies.
     *
     * @param  db           the JDBC template wired by Spring
     * @param  userService  the user-lookup service used on session cache misses
     * @param  mapper       the shared Jackson {@link ObjectMapper}
     * @since   v2026.1.0
     */
    public AuthService(JdbcTemplate db, UserService userService, ObjectMapper mapper) {
        this.db = db;
        this.userService = userService;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // Inner type
    // -------------------------------------------------------------------------

    /**
     * Immutable-ish cache entry that pairs a {@link User} with its session expiry time.
     *
     * <p>Fields are {@code public} so that {@link AuthService} callers (e.g.,
     * {@code AuthController}) can read the expiry timestamp without an additional method.
     *
     * @since   v2026.1.0
     */
    public static class CachedSession {

        /** The authenticated user associated with this session. */
        public User user;

        /** Session expiry expressed as a Unix epoch millisecond timestamp. */
        public long expiresAtMs;

        /**
         * Constructs a new cache entry.
         *
         * @param  user         the authenticated user; must not be {@code null}
         * @param  expiresAtMs  the absolute expiry time in milliseconds since the Unix epoch
         * @since   v2026.1.0
         */
        CachedSession(User user, long expiresAtMs) {
            this.user = user;
            this.expiresAtMs = expiresAtMs;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Extracts the raw session token string from the request cookies.
     *
     * <p>Iterates the cookie array looking for a cookie whose name equals
     * {@value #COOKIE_NAME}. Returns {@code null} if the cookie is absent or if the
     * request carries no cookies at all.
     *
     * @param  req  the current HTTP servlet request; must not be {@code null}
     * @return      the session token string, or {@code null} if not present
     * @since   v2026.1.0
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
     * <p>Checks the in-memory cache first (fast path). On a cache miss, queries the
     * {@code sessions} table, re-caches the result, and returns the user. Returns
     * {@code null} if the token is absent, blank, expired in both the cache and the DB,
     * or if the associated user record no longer exists.
     *
     * <p><b>Security:</b> relies on the token being a 256-bit cryptographically random
     * value — no additional HMAC or signature is verified here.
     *
     * @param  req  the current HTTP servlet request; must not be {@code null}
     * @return      the authenticated {@link User}, or {@code null} for any invalid/expired session
     * @since   v2026.1.0
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

            // Re-cache for subsequent requests in this JVM instance
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
     * persisted to the {@code sessions} table, and added to the in-memory cache.
     * The {@code Set-Cookie} header is written manually to include {@code SameSite=Lax}
     * (not supported by the Servlet API directly) and the optional {@code Secure} flag.
     *
     * <p><b>Security:</b> session rotation prevents session fixation attacks. The
     * {@code HttpOnly} flag prevents JavaScript access to the cookie.
     *
     * @param  res   the HTTP servlet response on which to set the cookie; must not be {@code null}
     * @param  req   the current HTTP servlet request, used to detect and invalidate the old session
     * @param  user  the authenticated user to associate with the new session; must not be {@code null}
     * @since   v2026.1.0
     */
    public void setSession(HttpServletResponse res, HttpServletRequest req, User user) {
        // Session rotation: invalidate any existing session cookie before issuing a new one
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

        // Set HttpOnly cookie via Servlet API first (for compatibility)
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) SESSION_DURATION_SECONDS);
        res.addCookie(cookie);

        // Override with a full Set-Cookie header to include SameSite=Lax and optional Secure
        String secureFlag = cookieSecure ? "; Secure" : "";
        res.addHeader("Set-Cookie", COOKIE_NAME + "=" + token
                + "; HttpOnly; SameSite=Lax; Path=/; Max-Age=" + SESSION_DURATION_SECONDS + secureFlag);
    }

    /**
     * Invalidates a session token and instructs the browser to delete the cookie.
     *
     * <p>Removes the token from both the in-memory cache and the {@code sessions} table,
     * then emits a {@code Set-Cookie} header with {@code Max-Age=0} to clear the browser
     * cookie. Safe to call with a {@code null} token (no-op for the DB and cache).
     *
     * @param  res    the HTTP servlet response on which to clear the cookie; must not be {@code null}
     * @param  token  the session token to invalidate; may be {@code null}
     * @since   v2026.1.0
     */
    public void clearSession(HttpServletResponse res, String token) {
        if (token != null) {
            removeSession(token);
        }
        res.addHeader("Set-Cookie", COOKIE_NAME + "=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
    }

    /**
     * Removes a single session token from the in-memory cache and from the DB.
     *
     * <p>DB errors are logged as warnings rather than propagated, so a DB failure does
     * not prevent the in-memory cache from being updated.
     *
     * @param  token  the session token to remove; must not be {@code null}
     * @since   v2026.1.0
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
     * Force-invalidates every active session for a specific user from the cache and the DB.
     *
     * <p>Intended to be called after a password reset, role change, or account deactivation
     * to ensure the new permissions (or suspension) take effect immediately without waiting
     * for session expiry.
     *
     * @param  userId  the ID of the user whose sessions should be terminated; must not be {@code null}
     * @since   v2026.1.0
     */
    public void forceLogoutUser(String userId) {
        sessions.entrySet().removeIf(e -> userId.equals(e.getValue().user.id));
        db.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    /**
     * Alias for {@link #forceLogoutUser(String)}.
     *
     * <p>Provided so callers that think in terms of "invalidating sessions" rather than
     * "forcing logout" have a semantically clear entry point.
     *
     * @param  userId  the ID of the user whose sessions should be invalidated; must not be {@code null}
     * @since   v2026.1.0
     */
    public void invalidateUserSessions(String userId) {
        forceLogoutUser(userId);
    }

    /**
     * Scheduled task that sweeps expired sessions from the DB and the in-memory cache.
     *
     * <p>Runs every hour with a 60-second initial delay after application startup.
     * Prevents unbounded growth of the {@code sessions} table and the {@link ConcurrentHashMap}.
     * DB errors are swallowed to avoid disrupting the scheduler thread.
     *
     * @since   v2026.1.0
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a raw byte array to a lowercase hexadecimal string.
     *
     * <p>Used to encode the 32-byte {@link SecureRandom} token into the 64-character
     * hex string that is stored in the DB and sent as a cookie value.
     *
     * @param  bytes  the byte array to encode; must not be {@code null}
     * @return        the lowercase hex string representation, always {@code bytes.length * 2} characters long
     * @since   v2026.1.0
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
