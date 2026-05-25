package com.ollanest.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the employee-facing HTML pages for the User Workspace (port 8081).
 *
 * <ul>
 *   <li>{@code GET /}      — redirects to {@code /login}</li>
 *   <li>{@code GET /login} — employee sign-in page</li>
 *   <li>{@code GET /app}   — main AI workspace (requires auth)</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
@Controller
public class UserPageController extends BaseController {

	/** Redirects root URL to the login page. */
	@GetMapping("/")
	public String root() {
		return "redirect:/login";
	}

	/** Serves the login page, or bounces already-authenticated users to {@code /app}. */
	@GetMapping("/login")
	public String login(HttpServletRequest req) {
		if (getUser(req) != null)
			return "redirect:/app";
		return "forward:/login.html";
	}

	/** Serves the main workspace, or redirects unauthenticated users to {@code /login}. */
	@GetMapping("/app")
	public String app(HttpServletRequest req) {
		if (getUser(req) == null)
			return "redirect:/login";
		return "forward:/app.html";
	}
}
