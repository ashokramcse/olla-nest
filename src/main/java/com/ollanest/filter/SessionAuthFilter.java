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
 * <p>Reads the {@code olla_nest_session} cookie from the request, delegates
 * validation and user resolution to {@link AuthService#getSessionUser}, and — if
 * a valid session is found — sets the resolved {@link User} as the
 * {@code "authenticatedUser"} request attribute. Downstream controllers retrieve
 * this attribute via {@link com.ollanest.controller.BaseController#getUser}.
 *
 * <p>This filter does not short-circuit the request for unauthenticated paths.
 * It is the responsibility of each controller to call
 * {@code requireAuth()} / {@code requireAdmin()} to enforce access. The following
 * API paths are intentionally unauthenticated and will not have a user attribute set:
 * <ul>
 *   <li>{@code POST /api/auth/login}</li>
 *   <li>{@code GET /api/bootstrap}</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Session tokens are cached in an in-memory {@code ConcurrentHashMap} inside
 *       {@link AuthService} to avoid a DB round-trip on every request.</li>
 *   <li>The filter always calls {@code filterChain.doFilter()} regardless of
 *       authentication outcome, keeping routing concerns in controllers.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Resolves and validates session cookies against the session store. */
    private final AuthService authService;

    /**
     * Constructor-injects the authentication service.
     *
     * @param  authService  the service used to look up and validate session tokens
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public SessionAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Extracts and validates the session cookie, then sets the authenticated user
     * as a request attribute before passing the request down the filter chain.
     *
     * <p>If no valid session cookie is present or the session has expired, no
     * attribute is set and the request continues unauthenticated (controllers will
     * return 401 as appropriate).
     *
     * @param  request      the incoming HTTP request
     * @param  response     the HTTP response
     * @param  filterChain  the remaining filter chain to invoke
     * @throws ServletException  if the next filter throws a servlet error
     * @throws IOException       if an I/O error occurs during filter chaining
     * @since   v2026.1.0  — initial Java Spring Boot migration
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
