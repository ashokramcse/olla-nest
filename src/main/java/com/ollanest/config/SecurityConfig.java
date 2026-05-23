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
 * <p>Disables all of Spring Security's default protective mechanisms (form login,
 * HTTP Basic, CSRF token, default session management, and default security headers)
 * so that they do not interfere with Olla Nest's own cookie-based session
 * authentication, which is handled entirely by {@link SessionAuthFilter} and
 * {@link SecurityHeadersFilter}.
 *
 * <p>All HTTP requests are permitted at the Spring Security layer; actual
 * authentication and authorisation enforcement happens inside the custom filters
 * and the {@code BaseController} helper methods.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Spring Security is kept on the classpath for its filter-chain infrastructure
 *       and BCrypt support, but its opinionated defaults are fully opted out of.</li>
 *   <li>CSRF protection is implemented manually via the {@code X-Olla-CSRF} header
 *       check in {@code BaseController.requireAuthWithCsrf()}.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
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
     * Security's defaults.
     *
     * @param  sessionAuthFilter     the filter that validates session cookies
     * @param  securityHeadersFilter the filter that sets HTTP security headers
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public SecurityConfig(SessionAuthFilter sessionAuthFilter, SecurityHeadersFilter securityHeadersFilter) {
        this.sessionAuthFilter = sessionAuthFilter;
        this.securityHeadersFilter = securityHeadersFilter;
    }

    /**
     * Builds and returns the application's {@link SecurityFilterChain}.
     *
     * <p>The chain is configured to:
     * <ul>
     *   <li>Disable CSRF (handled manually via {@code X-Olla-CSRF} header)</li>
     *   <li>Disable form login and HTTP Basic</li>
     *   <li>Use stateless session policy (Olla Nest manages sessions in SQLite)</li>
     *   <li>Permit all requests (auth enforced by {@link SessionAuthFilter})</li>
     *   <li>Disable default Spring Security headers ({@link SecurityHeadersFilter}
     *       sets its own)</li>
     *   <li>Insert both custom filters before Spring's
     *       {@code UsernamePasswordAuthenticationFilter}</li>
     * </ul>
     *
     * @param  http  the {@link HttpSecurity} builder provided by Spring Security
     * @return       the configured {@link SecurityFilterChain}
     * @throws Exception  if Spring Security configuration fails
     * @since   v2026.1.0  — initial Java Spring Boot migration
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
