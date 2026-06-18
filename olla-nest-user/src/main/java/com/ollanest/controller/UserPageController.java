package com.ollanest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the employee-facing HTML pages for the User Workspace (port 8081).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The employee workspace is a static single-page app, but the entry routes need
 * server-side auth checks so the right HTML is forwarded and unauthenticated
 * users are bounced to the login page. This controller owns those page routes;
 * it returns view forwards/redirects rather than JSON.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Auth gating happens here (not just in the SPA) so a stale cache cannot
 * expose the workspace shell to an anonymous visitor.</li>
 * <li>Root {@code /} redirects to {@code /login} as the canonical entry point.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.5 — initial user page routing</li>
 * </ul>
 *
 * <pre>
 *   GET /      — redirects to /login
 *   GET /login — employee sign-in page (bounces logged-in users to /app)
 *   GET /app   — main AI workspace (authentication required)
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.5
 */
@Controller
public class UserPageController extends BaseController {

	/**
	 * Redirects the root URL to the login page.
	 *
	 * @return a redirect view name pointing at {@code /login}
	 * @since v2026.1.5
	 */
	@GetMapping("/")
	public String root() {
		return "redirect:/login";
	}

	/**
	 * Serves the login page, or bounces already-authenticated users to
	 * {@code /app}.
	 *
	 * @param req the current HTTP request (to read the authenticated user, if any)
	 * @return a forward to {@code login.html}, or a redirect to {@code /app} when
	 *         already authenticated
	 * @since v2026.1.5
	 */
	@GetMapping("/login")
	public String login(HttpServletRequest req) {
		if (getUser(req) != null)
			return "redirect:/app";
		return "forward:/login.html";
	}

	/**
	 * Serves the main workspace, or redirects unauthenticated users to
	 * {@code /login}.
	 *
	 * @param req the current HTTP request (to read the authenticated user, if any)
	 * @return a forward to {@code app.html} when authenticated, otherwise a
	 *         redirect to {@code /login}
	 * @since v2026.1.5
	 */
	@GetMapping("/app")
	public String app(HttpServletRequest req) {
		if (getUser(req) == null)
			return "redirect:/login";
		return "forward:/app.html";
	}
}
