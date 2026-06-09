package com.ollanest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Smoke tests for {@link WebConfig}.
 *
 * <p>
 * Verifies that the MVC static-resource configuration class can be instantiated
 * without a Spring application context.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebConfig — unit tests")
class WebConfigTest {

	@Test
	@DisplayName("WebConfig instantiates without throwing")
	void constructionSucceeds() {
		// No exception thrown = static-resource config wires without a Spring context
		assertThatCode(WebConfig::new).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("WebConfig instance is non-null")
	void instanceIsNotNull() {
		// Constructor must return a usable instance
		assertThat(new WebConfig()).isNotNull();
	}
}
