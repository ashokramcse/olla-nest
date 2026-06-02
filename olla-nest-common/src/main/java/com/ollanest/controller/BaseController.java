package com.ollanest.controller;

import com.ollanest.model.User;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * Abstract base class for all Olla Nest REST controllers.
 *
 * Provides two auth patterns:
 *   1. Legacy pattern (existing controllers):
 *      {@code ResponseEntity<Map<String,Object>> err = guardAuth(req); if (err != null) return err;}
 *   2. Modern pattern (new controllers):
 *      {@code User user = requireAuth(req);} — throws AuthException (mapped to 401) if not logged in.
 *
 * Also provides response factory helpers: ok(), created(), badRequest(), notFound(), serverError().
 */
public abstract class BaseController {

    // ── Response factory helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> ok(Object body) {
        return (ResponseEntity<T>) ResponseEntity.ok(body);
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> created(Object body) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> badRequest(String message) {
        return (ResponseEntity<T>) ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> notFound(String message) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("ok", false, "error", message));
    }

    @SuppressWarnings("unchecked")
    protected <T> ResponseEntity<T> serverError(String message) {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("ok", false, "error", message));
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the authenticated {@link User} from the request attribute set by
     * {@link com.ollanest.filter.SessionAuthFilter}, or {@code null}.
     */
    protected User getUser(HttpServletRequest req) {
        return (User) req.getAttribute("authenticatedUser");
    }

    /**
     * Modern auth check — returns the authenticated User or throws AuthException (maps to 401).
     * Use in new-style controllers where the return type is ResponseEntity.
     */
    protected User requireAuth(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) throw new AuthException("Login required");
        return user;
    }

    /**
     * Modern admin check — returns the authenticated User or throws AuthException/ForbiddenException.
     */
    protected User requireAdminUser(HttpServletRequest req) {
        User user = requireAuth(req);
        if (!"admin".equals(user.role)) throw new ForbiddenException("Admin access required");
        return user;
    }

    /**
     * Legacy auth guard — returns 401 ResponseEntity if not authenticated, else null.
     * Used by existing controllers that follow the old pattern.
     */
    protected ResponseEntity<Map<String, Object>> guardAuth(HttpServletRequest req) {
        if (getUser(req) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Login required"));
        }
        return null;
    }

    /**
     * Legacy admin guard — returns 401/403 ResponseEntity if not admin, else null.
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
     * Legacy CSRF+auth guard — any authenticated user, state-changing requests require CSRF header.
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

    /** True if the CSRF check passes (GET always passes; others need X-Requested-With). */
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

    /** Strips HTML to prevent stored XSS. */
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
     * Legacy: alias so existing code that calls requireAdmin(req) still compiles.
     * Returns a 401/403 ResponseEntity or null.
     */
    protected ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
        return guardAdmin(req);
    }
}
