package com.ollanest.filter;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@link OncePerRequestFilter} that authenticates every HTTP request via the
 * Olla Nest session cookie.
 *
 * <h3>Why this class exists</h3>
 * <p>Spring Security's default form-login and HTTP-Basic authentication mechanisms
 * are disabled in Olla Nest. Instead, all authentication is cookie-based: the
 * client receives an {@code olla_nest_session} cookie on login and sends it with
 * every subsequent request. This filter reads that cookie, delegates validation to
 * {@link AuthService#getSessionUser}, and — on success — stores the resolved
 * {@link User} as a request attribute that controllers can read without touching
 * the session store again.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The filter never short-circuits the request for unauthenticated paths;
 *       access control is enforced by {@code BaseController.requireAuth()} and
 *       {@code BaseController.requireAdmin()}.</li>
 *   <li>Session tokens are validated by {@link AuthService}, which caches them in a
 *       {@code ConcurrentHashMap} to avoid a DB round-trip on every request.</li>
 *   <li>The following paths are intentionally served without a user attribute:
 *       {@code POST /api/auth/login} and {@code GET /api/bootstrap}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial migration from Node.js session-cookie middleware</li>
 *   <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Resolves and validates session cookies against the session store. */
    private final AuthService authService;

    /**
     * Constructor-injects the authentication service.
     *
     * @param  authService  the service used to look up and validate session tokens
     * @since  v2026.1.0
     */
    public SessionAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Extracts and validates the session cookie, then sets the authenticated user
     * as a request attribute before passing the request down the filter chain.
     *
     * <p>If no valid session cookie is present, or if the session has expired,
     * no attribute is set and the request continues unauthenticated. Controllers
     * are responsible for returning {@code 401} for protected endpoints.
     *
     * @param  request      the incoming HTTP request
     * @param  response     the HTTP response
     * @param  filterChain  the remaining filter chain to invoke
     * @throws ServletException  if the next filter throws a servlet error
     * @throws IOException       if an I/O error occurs during filter chaining
     * @since  v2026.1.0
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        User user = authService.getSessionUser(request);
        if (user != null) {
            request.setAttribute("authenticatedUser", user);
        }
        filterChain.doFilter(request, response);
    }
}
