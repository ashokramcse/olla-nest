package com.ollanest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@link OncePerRequestFilter} that adds HTTP security headers to every response.
 *
 * <p>Applied before all other filters in the Spring Security chain (registered in
 * {@link com.ollanest.config.SecurityConfig}). Spring Security's own default
 * security headers are disabled so this filter has full control over the header set.
 *
 * <p>Headers set on every response:
 * <ul>
 *   <li><b>X-Content-Type-Options: nosniff</b> — prevents MIME-type sniffing</li>
 *   <li><b>X-Frame-Options: SAMEORIGIN</b> — prevents clickjacking</li>
 *   <li><b>X-XSS-Protection: 1; mode=block</b> — legacy XSS filter hint</li>
 *   <li><b>Referrer-Policy: strict-origin-when-cross-origin</b> — limits referrer leakage</li>
 *   <li><b>Content-Security-Policy</b> (MED-1) — restricts resource origins</li>
 *   <li><b>Strict-Transport-Security</b> (MED-2) — enforces HTTPS for 1 year</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>{@code 'unsafe-inline'} is permitted for scripts and styles because the
 *       frontend uses inline event handlers; a nonce-based CSP is a future
 *       improvement.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 * @version v2026.1.0  — security hardening: added CSP (MED-1) and HSTS (MED-2) headers
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Adds all security headers to the response, then passes control to the next filter.
     *
     * @param  request      the incoming HTTP request
     * @param  response     the HTTP response to which headers are added
     * @param  filterChain  the remaining filter chain to invoke
     * @throws ServletException  if the next filter throws a servlet error
     * @throws IOException       if an I/O error occurs during filter chaining
     * @since   v2026.1.0  — initial Java Spring Boot migration
     * @version v2026.1.0  — security hardening: added CSP and HSTS headers
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; "
                        + "frame-ancestors 'none'");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        filterChain.doFilter(request, response);
    }
}
