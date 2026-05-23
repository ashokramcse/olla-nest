package com.ollanest.config;

import com.ollanest.filter.SecurityHeadersFilter;
import com.ollanest.filter.SessionAuthFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security filter chain configuration for Olla Nest.
 *
 * <h3>Why this class exists</h3>
 * <p>Spring Security is retained on the classpath for its filter-chain infrastructure
 * and BCrypt password hashing support. However, all of its opinionated default
 * behaviours — form login, HTTP Basic, CSRF token management, default session
 * handling, and the standard security-header set — are explicitly disabled because
 * Olla Nest implements its own cookie-based session authentication via
 * {@link SessionAuthFilter} and its own header policy via {@link SecurityHeadersFilter}.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>All HTTP requests are permitted at the Spring Security layer; actual
 *       authentication and authorisation are enforced inside the custom filters
 *       and the {@code BaseController} helper methods ({@code requireAuth()},
 *       {@code requireAdmin()}).</li>
 *   <li>CSRF protection is implemented manually via the {@code X-Olla-CSRF} header
 *       check in {@code BaseController.requireAuthWithCsrf()}.</li>
 *   <li>Session creation policy is set to {@code STATELESS} because Olla Nest
 *       manages sessions in SQLite, not in the servlet container.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial migration; replaces Node.js express-session and
 *       bcryptjs middleware</li>
 *   <li>v2026.1.4 — no functional changes; retained as part of security audit pass</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Custom session-authentication filter injected into the Spring Security chain. */
    private final SessionAuthFilter sessionAuthFilter;

    /** Custom security-headers filter injected into the Spring Security chain. */
    private final SecurityHeadersFilter securityHeadersFilter;

    /**
     * Constructor-injects the two custom servlet filters that replace Spring
     * Security's built-in defaults.
     *
     * @param  sessionAuthFilter     the filter that validates Olla Nest session cookies
     * @param  securityHeadersFilter the filter that sets HTTP security response headers
     * @since  v2026.1.0
     */
    public SecurityConfig(SessionAuthFilter sessionAuthFilter,
            SecurityHeadersFilter securityHeadersFilter) {
        this.sessionAuthFilter = sessionAuthFilter;
        this.securityHeadersFilter = securityHeadersFilter;
    }

    /**
     * Builds and returns the application's {@link SecurityFilterChain}.
     *
     * <p>The chain is configured to:
     * <ul>
     *   <li>Disable CSRF (handled manually via the {@code X-Olla-CSRF} header)</li>
     *   <li>Disable form login and HTTP Basic authentication</li>
     *   <li>Use a stateless session policy (sessions are managed in SQLite)</li>
     *   <li>Permit all requests at the Spring Security layer (auth enforced by
     *       {@link SessionAuthFilter} and controller helpers)</li>
     *   <li>Disable the default Spring Security header set ({@link SecurityHeadersFilter}
     *       applies its own)</li>
     *   <li>Insert both custom filters before Spring's
     *       {@code UsernamePasswordAuthenticationFilter}</li>
     * </ul>
     *
     * @param  http  the {@link HttpSecurity} builder provided by Spring Security
     * @return       the configured {@link SecurityFilterChain}
     * @throws Exception  if Spring Security configuration fails
     * @since  v2026.1.0
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — we use X-Requested-With header check manually
                .csrf(csrf -> csrf.disable())
                // Disable default form login and basic auth
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                // Disable Spring Security sessions (we manage sessions ourselves)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Allow all requests — our custom SessionAuthFilter handles auth
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Disable Spring Security default headers (we set our own via
                // SecurityHeadersFilter)
                .headers(headers -> headers.disable())
                // Add our custom filters
                .addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
