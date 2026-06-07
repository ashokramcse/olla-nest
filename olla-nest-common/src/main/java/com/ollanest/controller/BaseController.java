package com.ollanest.controller;

import com.ollanest.model.User;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * Abstract base class shared by all Olla Nest REST controllers.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Every controller needs the same boilerplate: authenticate the caller, check
 * admin rights, validate the CSRF header, and produce consistent error responses.
 * Placing these helpers in a single base class keeps each concrete controller
 * focused on its own domain and ensures security checks are applied consistently
 * across the application.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Two auth patterns are supported. The <em>legacy pattern</em>
 * ({@link #guardAuth}, {@link #guardAdmin}) returns a {@link ResponseEntity} that
 * callers check against {@code null}. The <em>modern pattern</em>
 * ({@link #requireAuth}, {@link #requireAdminUser}) throws {@link AuthException}
 * or {@link ForbiddenException} which are mapped to HTTP 401/403 by the global
 * exception handler. New controllers should prefer the modern pattern.</li>
 * <li>The {@code @Deprecated} aliases ({@link #requireAuthLegacy} etc.) exist
 * only to keep existing controllers compiling without changes.</li>
 * <li>{@link #sanitizeText} strips HTML to prevent stored XSS and should be
 * applied to any user-supplied string that may be rendered in the UI.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.4 — added modern {@code requireAuth}/{@code requireAdminUser}
 * throwing pattern and {@link AuthException}/{@link ForbiddenException}</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
public abstract class BaseController {

    // ── Response factory helpers ──────────────────────────────────────────────

    /**
     * Wraps a body in a 200 OK response.
     *
     * @param <T>  response body type
     * @param body the response body
     * @return a 200 OK {@link ResponseEntity}
     * @since v2026.1.0
     */
    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> ok(Object body) {
        return (ResponseEntity<T>) ResponseEntity.ok(body);
    }

    /**
     * Wraps a body in a 201 Created response.
     *
     * @param <T>  response body type
     * @param body the response body
     * @return a 201 Created {@link ResponseEntity}
     * @since v2026.1.0
     */
    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> created(Object body) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Returns a 400 Bad Request response with a standard error map.
     *
     * @param <T>     response body type
     * @param message the human-readable error description
     * @return a 400 Bad Request {@link ResponseEntity}
     * @since v2026.1.0
     */
    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> badRequest(String message) {
        return (ResponseEntity<T>) ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    /**
     * Returns a 404 Not Found response with a standard error map.
     *
     * @param <T>     response body type
     * @param message the human-readable error description
     * @return a 404 Not Found {@link ResponseEntity}
     * @since v2026.1.0
     */
    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> notFound(String message) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("ok", false, "error", message));
    }

    /**
     * Returns a 500 Internal Server Error response with a standard error map.
     *
     * @param <T>     response body type
     * @param message the human-readable error description
     * @return a 500 Internal Server Error {@link ResponseEntity}
     * @since v2026.1.0
     */
    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> serverError(String message) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("ok", false, "error", message));
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the authenticated {@link User} from the request attribute set by
     * {@link com.ollanest.filter.SessionAuthFilter}, or {@code null} if unauthenticated.
     *
     * @param req the current HTTP request
     * @return the authenticated user, or {@code null}
     * @since v2026.1.0
     */
    protected User getUser(HttpServletRequest req) {
        return (User) req.getAttribute("authenticatedUser");
    }

    /**
     * Returns the authenticated user, or throws {@link AuthException} (mapped to HTTP 401)
     * if not logged in. Use in new-style controllers.
     *
     * @param req the current HTTP request
     * @return the authenticated user
     * @throws AuthException if the request is unauthenticated
     * @since v2026.1.4
     */
    protected User requireAuth(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) throw new AuthException("Login required");
        return user;
    }

    /**
     * Returns the authenticated admin user, or throws {@link AuthException} (HTTP 401)
     * if unauthenticated or {@link ForbiddenException} (HTTP 403) if not an admin.
     *
     * @param req the current HTTP request
     * @return the authenticated admin user
     * @throws AuthException      if the request is unauthenticated
     * @throws ForbiddenException if the authenticated user is not an admin
     * @since v2026.1.4
     */
    protected User requireAdminUser(HttpServletRequest req) {
        User user = requireAuth(req);
        if (!"admin".equals(user.role)) throw new ForbiddenException("Admin access required");
        return user;
    }

    /**
     * Legacy auth guard — returns a 401 {@link ResponseEntity} if not authenticated,
     * or {@code null} if the request is authenticated. Used by existing controllers.
     *
     * @param req the current HTTP request
     * @return a 401 response entity, or {@code null} if authenticated
     * @since v2026.1.0
     */
    protected ResponseEntity<Map<String, Object>> guardAuth(HttpServletRequest req) {
        if (getUser(req) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Login required"));
        }
        return null;
    }

    /**
     * Legacy admin guard — returns a 401 or 403 {@link ResponseEntity} if not admin,
     * or {@code null} if the request is from an authenticated admin.
     *
     * @param req the current HTTP request
     * @return a 401/403 response entity, or {@code null} if the caller is an admin
     * @since v2026.1.0
     */
    protected ResponseEntity<Map<String, Object>> guardAdmin(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Login required"));
        }
        if (!"admin".equals(user.role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "error", "Admin access required"));
        }
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    /**
     * Legacy CSRF + auth guard — returns a 401/403 {@link ResponseEntity} if not
     * authenticated or if a state-changing request is missing the
     * {@code X-Requested-With} CSRF header, or {@code null} on success.
     *
     * @param req the current HTTP request
     * @return a 401/403 response entity, or {@code null} if the request passes
     * @since v2026.1.0
     */
    protected ResponseEntity<Map<String, Object>> guardAuthWithCsrf(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Login required"));
        }
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    /**
     * Returns {@code true} if the CSRF check passes: GET requests always pass;
     * other methods require the {@code X-Requested-With} header to be present.
     *
     * @param req the current HTTP request
     * @return {@code true} if the CSRF check passes
     * @since v2026.1.0
     */
    protected boolean isCsrfOk(HttpServletRequest req) {
        return "GET".equals(req.getMethod()) || req.getHeader("x-requested-with") != null;
    }

    // ── Exception types ───────────────────────────────────────────────────────

    /** Thrown when authentication is required but not present. Maps to HTTP 401. */
    public static class AuthException extends RuntimeException {
        public AuthException(String msg) { super(msg); }
    }

    /** Thrown when the user lacks required permissions. Maps to HTTP 403. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String msg) { super(msg); }
    }

    /**
     * Strips HTML tags and escapes special characters to prevent stored XSS. Returns
     * {@code null} if {@code input} is {@code null}.
     *
     * @param input the raw user-supplied string, or {@code null}
     * @return the sanitized string, or {@code null}
     * @since v2026.1.0
     */
    public static String sanitizeText(String input) {
        if (input == null) return null;
        return HtmlUtils.htmlEscape(input.strip());
    }

    // ── Legacy compat: aliases for existing controllers ───────────────────────
    // These preserve the old API so existing controllers don't need changes.

    /** @deprecated Use {@link #guardAuth} */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> requireAuthLegacy(HttpServletRequest req) {
        return guardAuth(req);
    }

    /** @deprecated Use {@link #guardAdmin} */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> requireAdminLegacy(HttpServletRequest req) {
        return guardAdmin(req);
    }

    /** @deprecated Use {@link #guardAuthWithCsrf} */
    @Deprecated
    protected ResponseEntity<Map<String, Object>> requireAuthWithCsrf(HttpServletRequest req) {
        return guardAuthWithCsrf(req);
    }

    /**
     * Legacy alias so existing code that calls {@code requireAdmin(req)} still compiles.
     * Delegates to {@link #guardAdmin}.
     *
     * @param req the current HTTP request
     * @return a 401/403 response entity, or {@code null} if the caller is an admin
     * @since v2026.1.0
     */
    protected ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
        return guardAdmin(req);
    }
}
