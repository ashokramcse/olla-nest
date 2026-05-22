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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session management: in-memory Map + DB persistence.
 * Cookie name: olla_nest_session
 * Sessions expire after 12 hours (matching Node.js code: 43200s).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String COOKIE_NAME = "olla_nest_session";
    private static final long SESSION_DURATION_SECONDS = 43200; // 12 hours

    @Value("${app.cookie-secure:false}")
    private boolean cookieSecure;

    // In-memory session cache: token -> User
    private final ConcurrentHashMap<String, CachedSession> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate db;
    private final UserService userService;
    private final ObjectMapper mapper;

    public AuthService(JdbcTemplate db, UserService userService, ObjectMapper mapper) {
        this.db = db;
        this.userService = userService;
        this.mapper = mapper;
    }

    public static class CachedSession {
        public User user;
        public long expiresAtMs;
        CachedSession(User user, long expiresAtMs) {
            this.user = user;
            this.expiresAtMs = expiresAtMs;
        }
    }

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

    public User getSessionUser(HttpServletRequest req) {
        String token = getToken(req);
        if (token == null || token.isBlank()) return null;

        // Check in-memory cache first
        CachedSession cached = sessions.get(token);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAtMs) {
                return cached.user;
            } else {
                sessions.remove(token);
            }
        }

        // Fall back to DB
        try {
            List<Map<String, Object>> rows = db.queryForList(
                "SELECT s.user_id, s.expires_at FROM sessions s WHERE s.token = ? AND s.expires_at > datetime('now')",
                token);
            if (rows.isEmpty()) return null;
            String userId = (String) rows.get(0).get("user_id");
            User user = userService.findUserById(userId);
            if (user == null) return null;

            // Cache it
            String expiresAtStr = (String) rows.get(0).get("expires_at");
            long expiresMs = System.currentTimeMillis() + SESSION_DURATION_SECONDS * 1000;
            sessions.put(token, new CachedSession(user, expiresMs));
            return user;
        } catch (Exception e) {
            log.error("[auth] Session lookup error: {}", e.getMessage());
            return null;
        }
    }

    public void setSession(HttpServletResponse res, HttpServletRequest req, User user) {
        // Invalidate old session if present
        String oldToken = getToken(req);
        if (oldToken != null) {
            removeSession(oldToken);
        }

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = bytesToHex(bytes);
        long expiresMs = System.currentTimeMillis() + SESSION_DURATION_SECONDS * 1000;
        String expiresAt = Instant.ofEpochMilli(expiresMs).toString().replace("T", " ").replace("Z", "");

        db.update("INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
            token, user.id, expiresAt);

        sessions.put(token, new CachedSession(user, expiresMs));

        // Set HttpOnly cookie
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) SESSION_DURATION_SECONDS);
        // SameSite=Lax via header (Cookie API doesn't support it directly in older servlet API)
        res.addCookie(cookie);
        // Override with full Set-Cookie header to include SameSite=Lax and optional Secure flag
        String secureFlag = cookieSecure ? "; Secure" : "";
        res.addHeader("Set-Cookie",
            COOKIE_NAME + "=" + token + "; HttpOnly; SameSite=Lax; Path=/; Max-Age=" + SESSION_DURATION_SECONDS + secureFlag);
    }

    public void clearSession(HttpServletResponse res, String token) {
        if (token != null) {
            removeSession(token);
        }
        res.addHeader("Set-Cookie",
            COOKIE_NAME + "=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0");
    }

    public void removeSession(String token) {
        sessions.remove(token);
        try {
            db.update("DELETE FROM sessions WHERE token = ?", token);
        } catch (Exception e) {
            log.warn("[auth] Failed to delete session from DB: {}", e.getMessage());
        }
    }

    public void forceLogoutUser(String userId) {
        // Remove from memory
        sessions.entrySet().removeIf(e -> userId.equals(e.getValue().user.id));
        // Remove from DB
        db.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    public void invalidateUserSessions(String userId) {
        forceLogoutUser(userId);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000) // every hour
    public void cleanExpiredSessions() {
        try {
            db.update("DELETE FROM sessions WHERE expires_at < datetime('now')");
            sessions.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue().expiresAtMs);
        } catch (Exception e) {
            log.warn("[auth] Failed to clean expired sessions: {}", e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
