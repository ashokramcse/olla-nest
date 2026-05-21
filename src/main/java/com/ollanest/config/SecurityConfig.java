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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SessionAuthFilter sessionAuthFilter;
    private final SecurityHeadersFilter securityHeadersFilter;

    public SecurityConfig(SessionAuthFilter sessionAuthFilter, SecurityHeadersFilter securityHeadersFilter) {
        this.sessionAuthFilter = sessionAuthFilter;
        this.securityHeadersFilter = securityHeadersFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — we use X-Requested-With header check manually
            .csrf(csrf -> csrf.disable())
            // Disable default form login and basic auth
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            // Disable Spring Security sessions (we manage sessions ourselves)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Allow all requests — our custom SessionAuthFilter handles auth
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Disable Spring Security default headers (we set our own via SecurityHeadersFilter)
            .headers(headers -> headers.disable())
            // Add our custom filters
            .addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
