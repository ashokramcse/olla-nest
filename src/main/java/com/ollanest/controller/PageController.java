package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;

/**
 * MVC controller that serves the frontend HTML pages for browser navigation.
 *
 * <p>Handles direct URL access to the three application entry points:
 * {@code /login}, {@code /app}, and {@code /admin}. Each handler validates
 * authentication state and either forwards to the appropriate HTML file or
 * redirects the user to the correct page.
 *
 * <p>Without this controller, Spring MVC would return 404 for direct URL
 * navigation, breaking browser history navigation for the single-page frontend.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Uses {@code forward:/file.html} (not redirect) so the browser URL
 *       stays at the clean path (e.g. {@code /app}) while Spring serves the
 *       HTML file from the static directory.</li>
 *   <li>Authenticated users navigating to {@code /login} are bounced to
 *       {@code /app} to avoid the redundant login screen.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
@Controller
public class PageController extends BaseController {

    /** Used to check whether the current request carries a valid session. */
    private final AuthService authService;

    /** Path to the directory containing the compiled frontend HTML files. */
    @Value("${app.static-dir:./public}")
    private String staticDir;

    /**
     * Constructor-injects the authentication service.
     *
     * @param  authService  the service used to read session state
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    public PageController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Redirects the root URL {@code /} to {@code /login}.
     *
     * @return  a redirect instruction to {@code /login}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    /**
     * Serves the login page, or redirects to {@code /app} if already authenticated.
     *
     * @param  req  the current HTTP request (used to check session state)
     * @return      forward to {@code login.html}, or redirect to {@code /app}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/login")
    public String login(HttpServletRequest req) {
        if (getUser(req) != null) return "redirect:/app";
        return "forward:/login.html";
    }

    /**
     * Serves the main application page, or redirects to {@code /login} if not
     * authenticated.
     *
     * @param  req  the current HTTP request (used to check session state)
     * @return      forward to {@code app.html}, or redirect to {@code /login}
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/app")
    public String app(HttpServletRequest req) {
        if (getUser(req) == null) return "redirect:/login";
        return "forward:/app.html";
    }

    /**
     * Serves the admin login page. Redirects admins to {@code /admin} and
     * non-admin authenticated users to {@code /app}.
     *
     * @param  req  the current HTTP request (used to check session state and role)
     * @return      forward to {@code admin-login.html}, or a redirect
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/admin-login")
    public String adminLogin(HttpServletRequest req) {
        User user = getUser(req);
        if (user != null && "admin".equals(user.role)) return "redirect:/admin";
        if (user != null) return "redirect:/app";
        return "forward:/admin-login.html";
    }

    /**
     * Serves the admin dashboard page. Redirects unauthenticated users to
     * {@code /admin-login} and non-admin users to {@code /app}.
     *
     * @param  req  the current HTTP request (used to check session state and role)
     * @return      forward to {@code admin.html}, or a redirect
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @GetMapping("/admin")
    public String admin(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) return "redirect:/admin-login";
        if (!"admin".equals(user.role)) return "redirect:/app";
        return "forward:/admin.html";
    }
}
