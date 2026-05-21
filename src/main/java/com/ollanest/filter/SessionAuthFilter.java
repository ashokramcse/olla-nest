package com.ollanest.filter;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the olla_nest_session cookie and sets the authenticated user
 * as a request attribute for downstream handlers.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public SessionAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        User user = authService.getSessionUser(request);
        if (user != null) {
            request.setAttribute("authenticatedUser", user);
        }
        filterChain.doFilter(request, response);
    }
}
