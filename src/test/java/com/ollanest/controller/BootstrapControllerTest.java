package com.ollanest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the public bootstrap endpoint.
 *
 * <p>{@code GET /api/bootstrap} is the first call the browser makes — it must
 * always return 200 with {@code ready:true} so the frontend knows the server
 * is up.
 *
 * @author Ashok Ram
 * @since v2026.1.9
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Bootstrap API")
class BootstrapControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("GET /api/bootstrap — returns 200 with ready:true")
	void bootstrapReturnsReady() throws Exception {
		mvc.perform(get("/api/bootstrap").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.ready").value(true));
	}

	@Test
	@DisplayName("GET /api/bootstrap — response contains firstBoot field")
	void bootstrapContainsFirstBoot() throws Exception {
		mvc.perform(get("/api/bootstrap").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.firstBoot").exists());
	}
}
