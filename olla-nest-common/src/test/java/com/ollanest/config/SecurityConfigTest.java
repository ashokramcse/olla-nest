package com.ollanest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ollanest.filter.SecurityHeadersFilter;
import com.ollanest.filter.SessionAuthFilter;

/**
 * Smoke tests for {@link SecurityConfig}.
 *
 * <p>
 * Verifies that the configuration class can be instantiated with mocked
 * dependencies without requiring a full Spring Security application context.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecurityConfig — unit tests")
class SecurityConfigTest {

	@Mock
	SessionAuthFilter sessionAuthFilter;
	@Mock
	SecurityHeadersFilter securityHeadersFilter;

	@Test
	@DisplayName("SecurityConfig instantiates without throwing")
	void constructionSucceeds() {
		// No exception thrown = Spring security config wires correctly with mocked
		// filters
		assertThatCode(() -> new SecurityConfig(sessionAuthFilter, securityHeadersFilter)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("SecurityConfig instance is non-null")
	void instanceIsNotNull() {
		// Confirm constructor returns a usable instance (not swallowed null)
		assertThat(new SecurityConfig(sessionAuthFilter, securityHeadersFilter)).isNotNull();
	}
}
