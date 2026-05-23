package com.ollanest.controller;

import com.ollanest.model.User;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Abstract base class for all Olla Nest REST controllers.
 *
 * <p>Provides shared helper methods for authentication, authorisation, and CSRF
 * validation so that individual controllers do not duplicate boilerplate. Every
 * public controller in the {@code com.ollanest.controller} package extends this
 * class.
 *
 * <p>The authentication model works as follows:
 * <ol>
 *   <li>{@link com.ollanest.filter.SessionAuthFilter} validates the session cookie
 *       and sets a {@link User} as the {@code "authenticatedUser"} request attribute.</li>
 *   <li>Controller methods call {@link #requireAuth} or {@link #requireAdmin} at the
 *       top of their body; if either returns a non-null {@link ResponseEntity}, the
 *       controller returns that directly (401 or 403).</li>
 *   <li>If the check returns {@code null}, the request is authorised and processing
 *       continues.</li>
 * </ol>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Returning a {@code ResponseEntity} (or {@code null}) from guard methods avoids
 *       exceptions for normal auth failures, keeping the hot path allocation-free.</li>
 *   <li>CSRF is checked via the {@code X-Requested-With} header (any non-null value
 *       satisfies the check) — a simple and widely supported pattern for same-origin
 *       detection.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
public abstract class BaseController {

    /**
     * Returns the authenticated {@link User} from the current request, or {@code null}
     * if the request is unauthenticated.
     *
     * <p>The attribute is set by {@link com.ollanest.filter.SessionAuthFilter} after
     * validating the session cookie.
     *
     * @param  req  the current HTTP request
     * @return      the authenticated {@link User}, or {@code null} if not logged in
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    protected User getUser(HttpServletRequest req) {
        return (User) req.getAttribute("authenticatedUser");
    }

    /**
     * Returns a 401 response if the request is unauthenticated, or {@code null}
     * if authentication is present.
     *
     * <p>Usage pattern:
     * <pre>{@code
     * ResponseEntity<?> authError = requireAuth(req);
     * if (authError != null) return authError;
     * }</pre>
     *
     * @param  req  the current HTTP request
     * @return      a 401 {@link ResponseEntity} if not authenticated, or {@code null}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    protected ResponseEntity<Map<String, Object>> requireAuth(HttpServletRequest req) {
        if (getUser(req) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Login required"));
        }
        return null;
    }

    /**
     * Returns a 401/403 response if the request is not authenticated as an admin,
     * or {@code null} if the admin check passes.
     *
     * <p>Enforces three checks in order:
     * <ol>
     *   <li>User is authenticated (401 if not).</li>
     *   <li>User role is {@code "admin"} (403 if not).</li>
     *   <li>Non-GET requests include the {@code X-Requested-With} header (CSRF guard).</li>
     * </ol>
     *
     * @param  req  the current HTTP request
     * @return      a 401 or 403 {@link ResponseEntity} if the check fails, or {@code null}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    protected ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Login required"));
        }
        if (!"admin".equals(user.role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Admin access required"));
        }
        // CSRF guard on state-changing requests
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    /**
     * Returns a 401/403 response if the request is not authenticated or is missing
     * the CSRF header on non-GET requests, or {@code null} if all checks pass.
     *
     * <p>Similar to {@link #requireAdmin} but without the role check — suitable for
     * any authenticated state-changing endpoint that any user may call.
     *
     * @param  req  the current HTTP request
     * @return      a 401 or 403 {@link ResponseEntity} if a check fails, or {@code null}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    protected ResponseEntity<Map<String, Object>> requireAuthWithCsrf(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Login required"));
        }
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    /**
     * Returns {@code true} if the CSRF check passes for the current request.
     *
     * <p>GET requests always pass. Non-GET requests pass only when the
     * {@code X-Requested-With} header is present (any non-null value).
     *
     * @param  req  the current HTTP request
     * @return      {@code true} if the CSRF check passes
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    protected boolean isCsrfOk(HttpServletRequest req) {
        return "GET".equals(req.getMethod()) || req.getHeader("x-requested-with") != null;
    }
}
