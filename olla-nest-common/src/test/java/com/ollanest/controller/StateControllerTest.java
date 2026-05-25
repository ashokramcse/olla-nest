package com.ollanest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the application-state endpoint.
 *
 * <p>{@code GET /api/state} is the single round-trip call that loads the entire
 * frontend on page load.  It requires an authenticated session.
 *
 * @author Ashok Ram
 * @since v2026.1.9
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("State API")
class StateControllerTest {

	@Autowired
	MockMvc mvc;

	private static final String LOGIN_URL = "/api/auth/login";
	private static final String STATE_URL = "/api/state";

	private static final String ADMIN_EMAIL = "admin@ollanest.local";
	private static final String ADMIN_PASS  = "junit-integration-test-only";

	// ── Unauthenticated access ────────────────────────────────────────────────

	@Test
	@DisplayName("GET /api/state — no session returns 401")
	void stateWithoutSession() throws Exception {
		mvc.perform(get(STATE_URL)
						.header("X-Requested-With", "XMLHttpRequest"))
				.andExpect(status().isUnauthorized());
	}

	// ── Authenticated access ──────────────────────────────────────────────────

	@Test
	@DisplayName("GET /api/state — authenticated admin receives user and state")
	void stateWithAdminSession() throws Exception {
		// Log in to obtain the session cookie
		MvcResult loginResult = mvc.perform(post(LOGIN_URL)
						.header("X-Requested-With", "XMLHttpRequest")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andReturn();

		Cookie[] cookies = loginResult.getResponse().getCookies();

		mvc.perform(get(STATE_URL)
						.header("X-Requested-With", "XMLHttpRequest")
						.cookie(cookies))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeUser").exists())
				.andExpect(jsonPath("$.activeUser.role").value("admin"));
	}
}
