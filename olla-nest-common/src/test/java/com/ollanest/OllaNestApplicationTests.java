package com.ollanest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies the Spring application context loads without errors.
 *
 * <p>If this test fails the application will not start in production either.
 *
 * @author Ashok Ram
 * @since v2026.1.9
 */
@SpringBootTest
@ActiveProfiles("test")
class OllaNestApplicationTests {

	@Test
	void contextLoads() {
		// Passes if the Spring context starts without throwing
	}
}
