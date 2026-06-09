package com.ollanest.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ollanest.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the admin-only HTML pages for the Admin Control Panel (port 8080).
 *
 * <ul>
 *   <li>{@code GET /admin-login} — admin sign-in page</li>
 *   <li>{@code GET /admin}       — admin dashboard (requires admin role)</li>
 *   <li>{@code GET /login}       — legacy/alias path, redirects to {@code /admin-login}</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
@Controller
public class AdminPageController extends BaseController {

	/**
	 * Redirects the user-app login path ({@code /login}) to the admin sign-in page.
	 *
	 * <p>The admin app's sign-in route is {@code /admin-login}; {@code /login} only
	 * exists on the user service. Older or browser-cached admin frontend code may
	 * still navigate here (e.g. after logout). Redirecting server-side makes the
	 * behaviour correct regardless of which JavaScript version the browser is
	 * running — it cannot be defeated by a stale cache or service worker.
	 */
	@GetMapping("/login")
	public String legacyLoginAlias() {
		return "redirect:/admin-login";
	}

	/**
	 * Serves the admin login page. Redirects authenticated admins straight to
	 * {@code /admin}, and non-admin authenticated users to {@code /app} on the
	 * user service.
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
