package com.ollanest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ollanest.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the admin-only HTML pages for the Admin Control Panel (port 8080).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The admin frontend is a static single-page app, but the entry routes need
 * server-side role checks and redirects so that the right HTML is forwarded and
 * unauthenticated or non-admin users are bounced appropriately. This controller
 * owns those page routes; it returns view forwards/redirects rather than JSON.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Role gating happens here (not just in the SPA) so a stale browser cache or
 * service worker cannot expose the dashboard shell to a non-admin.</li>
 * <li>{@code /login} is aliased server-side to {@code /admin-login} because the
 * canonical admin route differs from the user app's {@code /login}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.5 — initial admin page routing</li>
 * </ul>
 *
 * <pre>
 *   GET /login        — legacy alias, redirects to /admin-login
 *   GET /admin-login  — admin sign-in page (bounces logged-in users)
 *   GET /admin        — admin dashboard (admin role required)
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.5
 */
@Controller
public class AdminPageController extends BaseController {

	/**
	 * Redirects the user-app login path ({@code /login}) to the admin sign-in page.
	 *
	 * <p>
	 * The admin app's sign-in route is {@code /admin-login}; {@code /login} only
	 * exists on the user service. Older or browser-cached admin frontend code may
	 * still navigate here (e.g. after logout). Redirecting server-side makes the
	 * behaviour correct regardless of which JavaScript version the browser is
	 * running — it cannot be defeated by a stale cache or service worker.
	 *
	 * @return a redirect view name pointing at {@code /admin-login}
	 * @since v2026.1.5
	 */
	@GetMapping("/login")
	public String legacyLoginAlias() {
		return "redirect:/admin-login";
	}

	/**
	 * Serves the admin login page. Redirects authenticated admins straight to
	 * {@code /admin}, and non-admin authenticated users to {@code /app} on the user
	 * service.
	 *
	 * @param req the current HTTP request (to read the authenticated user, if any)
	 * @return a forward to {@code admin-login.html}, or a redirect to
	 *         {@code /admin} / {@code /app} for already-authenticated users
	 * @since v2026.1.5
	 */
	@GetMapping("/admin-login")
	public String adminLogin(HttpServletRequest req) {
		User user = getUser(req);
		if (user != null && "admin".equals(user.role))
			return "redirect:/admin";
		if (user != null)
			return "redirect:/app";
		return "forward:/admin-login.html";
	}

	/**
	 * Serves the admin dashboard. Unauthenticated requests go to
	 * {@code /admin-login}; non-admin users are redirected to {@code /app}.
	 *
	 * @param req the current HTTP request (to read the authenticated user, if any)
	 * @return a forward to {@code admin.html} for admins, or a redirect to
	 *         {@code /admin-login} / {@code /app} otherwise
	 * @since v2026.1.5
	 */
	@GetMapping("/admin")
	public String admin(HttpServletRequest req) {
		User user = getUser(req);
		if (user == null)
			return "redirect:/admin-login";
		if (!"admin".equals(user.role))
			return "redirect:/app";
		return "forward:/admin.html";
	}
}
