package com.ollanest.controller;

import com.ollanest.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the admin-only HTML pages for the Admin Control Panel (port 8080).
 *
 * <ul>
 *   <li>{@code GET /admin-login} — admin sign-in page</li>
 *   <li>{@code GET /admin}       — admin dashboard (requires admin role)</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
@Controller
public class AdminPageController extends BaseController {

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
