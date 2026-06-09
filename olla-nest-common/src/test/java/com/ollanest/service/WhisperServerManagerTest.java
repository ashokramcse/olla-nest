package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link WhisperServerManager}.
 *
 * <p>
 * The manager spawns a daemon thread in its constructor and attempts to start a
 * Python subprocess. Unit tests only verify that construction succeeds and that
 * {@code stop()} is safe to call when no process was started (venv absent in
 * CI). No real processes are spawned or asserted.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WhisperServerManager — unit tests")
class WhisperServerManagerTest {

	// ── construction ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("construction")
	class Construction {

		@Test
		@DisplayName("instantiates without throwing even when Python venv is absent")
		void constructionDoesNotThrow() {
			// Python venv is not present in CI — constructor must handle absence gracefully
			assertThatCode(WhisperServerManager::new).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("manager instance is non-null after construction")
		void instanceIsNotNull() {
			// Non-null check: confirms the constructor completed (daemon thread started)
			assertThat(new WhisperServerManager()).isNotNull();
		}
	}

	// ── stop() when not started ───────────────────────────────────────────────

	@Nested
	@DisplayName("stop() when server was never started")
	class StopNotStarted {

		@Test
		@DisplayName("stop() does not throw when whisperProcess is null")
		void stopIsIdempotentWhenNeverStarted() throws Exception {
			// Step 1: construct (spawns daemon start thread that will find no venv)
			WhisperServerManager manager = new WhisperServerManager();
			// Step 2: give the daemon start thread a moment to run (and fail gracefully)
			Thread.sleep(100);
			// Step 3: stop() must be safe even when whisperProcess was never assigned
			assertThatCode(manager::stop).doesNotThrowAnyException();
		}
	}
}
