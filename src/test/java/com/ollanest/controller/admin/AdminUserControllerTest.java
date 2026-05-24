package com.ollanest.controller.admin;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the admin user-management endpoints.
 *
 * <p>Covers:
 * <ul>
 * <li>Unauthenticated access is rejected (401)</li>
 * <li>Admin can list users</li>
 * <li>Admin can create a new user</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.9
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Admin — User Management API")
class AdminUserControllerTest {

	@Autowired
	MockMvc mvc;

	private static final String LOGIN_URL = "/api/auth/login";
	private static final String USERS_URL = "/api/admin/users";

	private static final String ADMIN_EMAIL = "admin@ollanest.local";
	private static final String ADMIN_PASS  = "CHANGE_ME_ON_FIRST_BOOT";

	/** Cookies from a successful admin login, re-used across tests in the class. */
	private Cookie[] adminCookies;

	@BeforeEach
	void loginAsAdmin() throws Exception {
		MvcResult result = mvc.perform(post(LOGIN_URL)
						.header("X-Requested-With", "XMLHttpRequest")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andReturn();
		adminCookies = result.getResponse().getCookies();
	}

	// ── Unauthenticated ───────────────────────────────────────────────────────

	@Test
	@DisplayName("GET /api/admin/users — no session returns 401")
	void listUsersWithoutSession() throws Exception {
		mvc.perform(get(USERS_URL)
						.header("X-Requested-With", "XMLHttpRequest"))
				.andExpect(status().isUnauthorized());
	}

	// ── Authenticated admin ───────────────────────────────────────────────────

	@Test
	@DisplayName("GET /api/admin/users — admin receives user list")
	void listUsersAsAdmin() throws Exception {
		mvc.perform(get(USERS_URL)
						.header("X-Requested-With", "XMLHttpRequest")
						.cookie(adminCookies))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.users").isArray());
	}

	@Test
	@DisplayName("POST /api/admin/users — admin can create a user")
	void createUserAsAdmin() throws Exception {
		String body = """
				{
				  "email":    "newuser-test@ollanest.local",
				  "name":     "Test User",
				  "role":     "user",
				  "password": "TestPass!9876"
				}
				""";

		mvc.perform(post(USERS_URL)
						.header("X-Requested-With", "XMLHttpRequest")
						.cookie(adminCookies)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
	}
}
