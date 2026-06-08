package com.ollanest.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ollanest.model.User;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Session management: in-memory cache backed by DB persistence.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Manages the full lifecycle of Olla Nest authentication sessions. The session
 * token is a 256-bit (64-hex-char) {@link SecureRandom} value stored in the
 * {@code sessions} table and cached in a {@link ConcurrentHashMap} for fast
 * per-request lookup. This class is the single authority for reading, creating,
 * and destroying session state — no other class should write to the
 * {@code sessions} table directly.
 *
 * <h3>Design notes</h3>
 * <p>
 * Cookie configuration:
 * <ul>
 * <li>Name: configurable via {@code app.session-cookie-name} (default
 * {@code olla_nest_session}). The admin and user apps set distinct names so they
 * do not share a cookie when running on the same host but different ports.</li>
 * <li>Flags: {@code HttpOnly; SameSite=Lax; Path=/}</li>
 * <li>Duration: 12 hours (43 200 seconds)</li>
 * <li>Optional {@code Secure} flag controlled by the {@code app.cookie-secure}
 * property</li>
 * </ul>
 *
 * <p>
 * Security features:
 * <ul>
 * <li>IP-based brute-force protection is handled by {@code AuthController} (not
 * here)</li>
 * <li>Session rotation on login — the old token is invalidated before a new one
 * is issued</li>
 * <li>Force-logout support for immediate revocation after a role or status
 * change</li>
 * <li>Scheduled hourly sweep removes expired rows from {@code sessions} and
 * from the cache</li>
 * </ul>
 *
 * <p>
 * The in-memory cache avoids a DB round-trip on every request. On a cache miss
 * (e.g., after a server restart) the session is re-loaded from the DB and
 * re-cached. Sessions are not renewed on use — they expire at a fixed time from
 * creation, consistent with the original Node.js behaviour.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * <li><b>v2026.1.4</b> — ok:false error responses standardised across auth
 * paths</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10 — MED-3: bounded session cache with size guard and
 *          expired-entry eviction
 */
@Service
public class AuthService {

	/** SLF4J logger for this service. */
	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/**
	 * Name of the session cookie set on the browser.
	 *
	 * <p>Configurable per service so that the admin and user applications — which
	 * run on the same host ({@code localhost}) but different ports — do not share a
	 * cookie. Cookies are scoped by host, not port, so a shared name would let a
	 * login (or logout) in one app clobber the other's session. Each app sets a
	 * distinct {@code app.session-cookie-name}; the default preserves the original
	 * single-app value.
	 */
	@Value("${app.session-cookie-name:olla_nest_session}")
	private String cookieName = "olla_nest_session";

	/**
	 * Shared SecureRandom instance — reused across all {@link #setSession} calls.
	 * Constructing a new SecureRandom per call is expensive (seeds from OS entropy)
	 * and unnecessarily drains the entropy pool. SecureRandom is thread-safe.
	 */
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	/**
	 * Expected length (in hex characters) of a valid session token.
	 * 32 random bytes → 64 lowercase hex characters.
	 */
	private static final int TOKEN_HEX_LENGTH = 64;

	/** Pre-compiled pattern for validating that a token is exactly 64 lowercase hex chars. */
	private static final java.util.regex.Pattern TOKEN_PATTERN =
			java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

	/** Session lifetime in seconds (12 hours). */
	private static final long SESSION_DURATION_SECONDS = 43200;

	/** Maximum number of sessions to hold in the in-memory cache. Prevents unbounded growth. */
	private static final int MAX_CACHE_SIZE = 10_000;

	/** Lock for cache eviction — prevents two concurrent setSession() calls from over-evicting. */
	private static final Object CACHE_EVICTION_LOCK = new Object();

	/**
	 * Whether to append the {@code Secure} flag to the session cookie. Should be
	 * {@code true} in production behind HTTPS; defaults to {@code false} so local
	 * development works without TLS.
	 */
	@Value("${app.cookie-secure:false}")
	private boolean cookieSecure;

	/** In-memory session cache: token → {@link CachedSession}. Thread-safe. */
	private final ConcurrentHashMap<String, CachedSession> sessions = new ConcurrentHashMap<>();

	/** JDBC template used for session persistence and expiry sweeps. */
	private final JdbcTemplate db;

	/** Loads the {@link User} object from the DB on a cache miss. */
	private final UserService userService;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db          the JDBC template wired by Spring
	 * @param userService the user-lookup service used on session cache misses
	 * @since v2026.1.0
	 */
	public AuthService(JdbcTemplate db, UserService userService) {
		this.db = db;
		this.userService = userService;
	}

	// -------------------------------------------------------------------------
	// Inner type
	// -------------------------------------------------------------------------

	/**
	 * Immutable-ish cache entry that pairs a {@link User} with its session expiry
	 * time.
	 *
	 * <p>
	 * Fields are {@code public} so that {@link AuthService} callers (e.g.,
	 * {@code AuthController}) can read the expiry timestamp without an additional
	 * method.
	 *
	 * @since v2026.1.0
	 */
	public static class CachedSession {

		/**
		 * The authenticated user associated with this session.
		 * Declared {@code final} to prevent external code from replacing the user
		 * reference on a live cached session (immutability defence).
		 */
		public final User user;

		/** Session expiry expressed as a Unix epoch millisecond timestamp. */
		public final long expiresAtMs;

		/**
		 * Constructs a new cache entry.
		 *
		 * @param user        the authenticated user; must not be {@code null}
		 * @param expiresAtMs the absolute expiry time in milliseconds since the Unix
		 *                    epoch
		 * @since v2026.1.0
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
	 * <p>
	 * Iterates the cookie array looking for a cookie whose name equals
	 * the configured session cookie name. Returns {@code null} if the cookie is absent or if the
	 * request carries no cookies at all.
	 *
	 * @param req the current HTTP servlet request; must not be {@code null}
	 * @return the session token string, or {@code null} if not present
	 * @since v2026.1.0
	 */
	public String getToken(HttpServletRequest req) {
		if (req.getCookies() != null) {
			for (Cookie c : req.getCookies()) {
				if (cookieName.equals(c.getName())) {
					return c.getValue();
				}
			}
		}
		return null;
	}

	/**
	 * Resolves the session cookie to a validated, non-expired {@link User}.
	 *
	 * <p>
	 * Checks the in-memory cache first (fast path). On a cache miss, queries the
	 * {@code sessions} table, re-caches the result, and returns the user. Returns
	 * {@code null} if the token is absent, blank, expired in both the cache and the
	 * DB, or if the associated user record no longer exists.
	 *
	 * <p>
	 * <b>Security:</b> relies on the token being a 256-bit cryptographically random
	 * value — no additional HMAC or signature is verified here.
	 *
	 * @param req the current HTTP servlet request; must not be {@code null}
	 * @return the authenticated {@link User}, or {@code null} for any
	 *         invalid/expired session
	 * @since v2026.1.0
	 */
	public User getSessionUser(HttpServletRequest req) {
		String token = getToken(req);
		if (token == null || token.isBlank())
			return null;

		// Reject tokens that don't match the exact 64-hex-char format.
		// This prevents excessively-long or malformed tokens from reaching the DB
		// and acts as a first-line defence against cookie-injection attacks.
		if (!TOKEN_PATTERN.matcher(token).matches())
			return null;

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
			List<Map<String, Object>> rows = db.queryForList("SELECT s.user_id, s.expires_at FROM sessions s "
					+ "WHERE s.token = ? AND s.expires_at > datetime('now')", token);
			if (rows.isEmpty())
				return null;
			String userId = (String) rows.get(0).get("user_id");
			User user = userService.findUserById(userId);
			if (user == null)
				return null;

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
	 * Creates a new session for the given user and sets the session cookie on the
	 * response.
	 *
	 * <p>
	 * If the request already carries a valid session cookie, the old session is
	 * invalidated first (session rotation). A 256-bit random token is generated,
	 * persisted to the {@code sessions} table, and added to the in-memory cache.
	 * The {@code Set-Cookie} header is written manually to include
	 * {@code SameSite=Lax} (not supported by the Servlet API directly) and the
	 * optional {@code Secure} flag.
	 *
	 * <p>
	 * <b>Security:</b> session rotation prevents session fixation attacks. The
	 * {@code HttpOnly} flag prevents JavaScript access to the cookie.
	 *
	 * @param res  the HTTP servlet response on which to set the cookie; must not be
	 *             {@code null}
	 * @param req  the current HTTP servlet request, used to detect and invalidate
	 *             the old session
	 * @param user the authenticated user to associate with the new session; must
	 *             not be {@code null}
	 * @since v2026.1.0
	 */
	public void setSession(HttpServletResponse res, HttpServletRequest req, User user) {
		// Session rotation: invalidate any existing session cookie before issuing a new
		// one
		String oldToken = getToken(req);
		if (oldToken != null) {
			removeSession(oldToken);
		}

		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		String token = bytesToHex(bytes);
		long expiresMs = System.currentTimeMillis() + SESSION_DURATION_SECONDS * 1000;
		String expiresAt = Instant.ofEpochMilli(expiresMs).toString().replace("T", " ").replace("Z", "");

		// MED-4 FIX: Evict under synchronization to prevent two concurrent threads
		// from both observing size >= MAX_CACHE_SIZE and over-evicting, or from
		// adding a new session that gets immediately removed by a racing eviction.
		// Use a dedicated lock object rather than synchronizing on 'sessions' itself
		// (which would serialize all ConcurrentHashMap operations, defeating its purpose).
		synchronized (CACHE_EVICTION_LOCK) {
			if (sessions.size() >= MAX_CACHE_SIZE) {
				// Pass 1: remove expired entries — O(n) but cheap in the common case
				sessions.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue().expiresAtMs);
				// Pass 2: if still over limit, evict the oldest 10%
				if (sessions.size() >= MAX_CACHE_SIZE) {
					int toRemove = MAX_CACHE_SIZE / 10;
					sessions.entrySet().stream()
							.sorted(Map.Entry.comparingByValue(Comparator.comparingLong(s -> s.expiresAtMs)))
							.limit(toRemove)
							.map(Map.Entry::getKey)
							.toList()  // collect before modifying the map
							.forEach(sessions::remove);
				}
			}
		}
		db.update("INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)", token, user.id, expiresAt);
		sessions.put(token, new CachedSession(user, expiresMs));

		// HIGH-1 FIX: Set cookie exclusively via the raw Set-Cookie header so that
		// SameSite=Lax is always included. Using both res.addCookie() AND a manual
		// Set-Cookie header results in two separate Set-Cookie directives for the
		// same cookie name; browsers process both, and the one without SameSite may
		// be stored instead of the hardened one on some older browsers.
		String secureFlag = cookieSecure ? "; Secure" : "";
		res.setHeader("Set-Cookie", cookieName + "=" + token
				+ "; HttpOnly; SameSite=Lax; Path=/; Max-Age=" + SESSION_DURATION_SECONDS + secureFlag);
	}

	/**
	 * Invalidates a session token and instructs the browser to delete the cookie.
	 *
	 * <p>
	 * Removes the token from both the in-memory cache and the {@code sessions}
	 * table, then emits a {@code Set-Cookie} header with {@code Max-Age=0} to clear
	 * the browser cookie. Safe to call with a {@code null} token (no-op for the DB
	 * and cache).
	 *
	 * @param res   the HTTP servlet response on which to clear the cookie; must not
	 *              be {@code null}
	 * @param token the session token to invalidate; may be {@code null}
	 * @since v2026.1.0
	 */
	public void clearSession(HttpServletResponse res, String token) {
		if (token != null) {
			removeSession(token);
		}
		res.addHeader("Set-Cookie", cookieName + "=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
	}

	/**
	 * Removes a single session token from the in-memory cache and from the DB.
	 *
	 * <p>
	 * DB errors are logged as warnings rather than propagated, so a DB failure does
	 * not prevent the in-memory cache from being updated.
	 *
	 * @param token the session token to remove; must not be {@code null}
	 * @since v2026.1.0
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
	 * Force-invalidates every active session for a specific user from the cache and
	 * the DB.
	 *
	 * <p>
	 * Intended to be called after a password reset, role change, or account
	 * deactivation to ensure the new permissions (or suspension) take effect
	 * immediately without waiting for session expiry.
	 *
	 * @param userId the ID of the user whose sessions should be terminated; must
	 *               not be {@code null}
	 * @since v2026.1.0
	 */
	public void forceLogoutUser(String userId) {
		sessions.entrySet().removeIf(e -> userId.equals(e.getValue().user.id));
		db.update("DELETE FROM sessions WHERE user_id = ?", userId);
	}

	/**
	 * Alias for {@link #forceLogoutUser(String)}.
	 *
	 * <p>
	 * Provided so callers that think in terms of "invalidating sessions" rather
	 * than "forcing logout" have a semantically clear entry point.
	 *
	 * @param userId the ID of the user whose sessions should be invalidated; must
	 *               not be {@code null}
	 * @since v2026.1.0
	 */
	public void invalidateUserSessions(String userId) {
		forceLogoutUser(userId);
	}

	/**
	 * Scheduled task that sweeps expired sessions from the DB and the in-memory
	 * cache.
	 *
	 * <p>
	 * Runs every hour with a 60-second initial delay after application startup.
	 * Prevents unbounded growth of the {@code sessions} table and the
	 * {@link ConcurrentHashMap}. DB errors are swallowed to avoid disrupting the
	 * scheduler thread.
	 *
	 * @since v2026.1.0
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
	 * <p>
	 * Used to encode the 32-byte {@link SecureRandom} token into the 64-character
	 * hex string that is stored in the DB and sent as a cookie value.
	 *
	 * @param bytes the byte array to encode; must not be {@code null}
	 * @return the lowercase hex string representation, always
	 *         {@code bytes.length * 2} characters long
	 * @since v2026.1.0
	 */
	private String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}
}
