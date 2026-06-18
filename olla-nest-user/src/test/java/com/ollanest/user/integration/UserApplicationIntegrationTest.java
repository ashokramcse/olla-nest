package com.ollanest.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.WhisperServerManager;
import com.ollanest.user.OllaNestUserApplication;

/**
 * Boot/context smoke tests for the Employee Workspace (user) Spring Boot app.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The user application previously had no full-context test at all — nothing
 * automatically verified that {@link OllaNestUserApplication} even starts. The
 * Spring Boot 4 upgrade proved why that matters: a production-only config issue
 * (a relocated {@code autoconfigure.exclude} class) crashed startup yet was
 * invisible to the entire unit-test suite. This class loads the full
 * application context — including the production {@code autoconfigure.exclude}
 * mirrored into the test profile — and smoke-tests the shared auth/security
 * surface through the real filter chain.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs as a {@link SpringBootTest} in the mock servlet environment with
 * {@link AutoConfigureMockMvc} and the {@code test} profile (in-memory SQLite +
 * Flyway).</li>
 * <li>External runtimes ({@link OllamaService}, {@link WhisperServerManager})
 * and the SQLite-specific {@link DatabaseService} are replaced with
 * {@link MockitoBean}s so the context loads offline without seeding.</li>
 * <li>{@link DirtiesContext} resets the context after the class so the shared
 * in-memory database does not bleed into other suites.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.2 — initial context-load + auth/security-header smoke coverage,
 * added during the Spring Boot 4.1 end-to-end hardening pass.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.2
 * @version v2026.2.2
 */
@SpringBootTest(classes = OllaNestUserApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("User App Integration — context load & security surface")
class UserApplicationIntegrationTest {

	/** MockMvc entry point driving requests through the full filter chain. */
	@Autowired
	MockMvc mockMvc;

	/** Mocked database service so seeding side-effects do not run during context load. */
	@MockitoBean
	DatabaseService databaseService;
	/** Mocked Ollama service so no local model runtime is required. */
	@MockitoBean
	OllamaService ollamaService;
	/** Mocked Whisper manager so no speech runtime is required. */
	@MockitoBean
	WhisperServerManager whisperServerManager;

	/**
	 * Verifies the full application context loads and MockMvc is wired.
	 *
	 * <p>
	 * Reaching this assertion means {@link OllaNestUserApplication} started with
	 * the production {@code autoconfigure.exclude} active — the exact failure mode
	 * that the Spring Boot 4 upgrade introduced and that no unit test could see.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.2
	 * @version v2026.2.2
	 */
	@Test
	@DisplayName("application context loads with production autoconfigure.exclude active")
	void contextLoads() {
		assertThat(mockMvc).isNotNull();
	}

	/**
	 * Verifies the shared auth endpoint reports unauthenticated without a session.
	 *
	 * <p>
	 * With no session cookie, {@code GET /api/auth/me} must return 200 with
	 * {@code authenticated=false} and a null user, proving the common
	 * {@code AuthController} and session filter are wired into the user app.
	 *
	 * @throws Exception if the MockMvc request fails
	 * @author Ashok Ram
	 * @since v2026.2.2
	 * @version v2026.2.2
	 */
	@Test
	@DisplayName("GET /api/auth/me returns authenticated=false without a session")
	void me_withoutSession_returnsUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(false))
				.andExpect(jsonPath("$.user").value(nullValue()));
	}

	/**
	 * Verifies the security-headers filter is active on user-app responses.
	 *
	 * <p>
	 * Every response must carry {@code X-Content-Type-Options: nosniff},
	 * {@code X-Frame-Options: DENY} and a {@code Content-Security-Policy},
	 * confirming the shared {@code SecurityHeadersFilter} runs in this app too.
	 *
	 * @throws Exception if the MockMvc request fails
	 * @author Ashok Ram
	 * @since v2026.2.2
	 * @version v2026.2.2
	 */
	@Test
	@DisplayName("security headers are present on every response")
	void securityHeaders_present() throws Exception {
		mockMvc.perform(get("/api/auth/me")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().exists("Content-Security-Policy"));
	}

	/**
	 * Verifies the global 404 handler returns the standard error envelope.
	 *
	 * <p>
	 * An unknown API path must yield 404 with {@code ok=false} and
	 * {@code error="Not found"}, matching the contract enforced across the apps.
	 *
	 * @throws Exception if the MockMvc request fails
	 * @author Ashok Ram
	 * @since v2026.2.2
	 * @version v2026.2.2
	 */
	@Test
	@DisplayName("unknown API path returns 404 with {ok:false, error:'Not found'}")
	void unknownPath_returns404Envelope() throws Exception {
		mockMvc.perform(get("/api/does-not-exist-at-all")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("Not found"));
	}
}
