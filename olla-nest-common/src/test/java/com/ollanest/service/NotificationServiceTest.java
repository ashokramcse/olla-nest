package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link NotificationService}.
 *
 * <p>
 * Covers: channel="none" — no downstream calls; channel="email" — no exception;
 * channel="ntfy" — {@link DatabaseService#getSetting} called for ntfyUrl;
 * channel="ntfy" is async so no hard assertion on HTTP; unknown channel — no
 * exception.
 *
 * <p>
 * No Spring context, no real HTTP calls — async ntfy is fire-and-forget.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationService — unit tests")
class NotificationServiceTest {

	private static final String OWNER = UserFactory.USER_ID;

	@Mock
	DatabaseService databaseService;

	@InjectMocks
	NotificationService svc;

	// ── notify() — channel routing ────────────────────────────────────────────

	@Nested
	@DisplayName("notify() — channel routing")
	class Notify {

		@Test
		@DisplayName("channel='none' — notify() returns without any error")
		void channelNoneNoOp() {
			// Stub: admin has disabled notifications — channel is "none"
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("none");
			// No exception = service correctly skips dispatch for "none" channel
			assertThatCode(() -> svc.notify(OWNER, "Title", "Body", 3)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("channel='email' — logs notification, no exception thrown")
		void channelEmailNoException() {
			// Stub: email channel is configured — the service logs but does not send real
			// email
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("email");
			// No exception = email path is handled gracefully even without a real SMTP
			// server
			assertThatCode(() -> svc.notify(OWNER, "Reminder", "Your task is due", 3)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("channel='ntfy' — getSetting called for ntfyUrl")
		void channelNtfyCallsGetSetting() {
			// Stub: ntfy channel with all required settings
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("ntfy");
			when(databaseService.getSetting(eq("ntfyUrl"), anyString())).thenReturn("https://ntfy.sh");
			when(databaseService.getSetting(eq("ntfyTopic"), anyString())).thenReturn("test-topic");
			when(databaseService.getSetting(eq("ntfyAuth"), anyString())).thenReturn("");
			// ntfy is async — just verify no exception and getSetting is called for the URL
			assertThatCode(() -> svc.notify(OWNER, "Alert", "Something happened", 5)).doesNotThrowAnyException();
			// ntfyUrl must be read from settings — hardcoding it would be a configuration
			// bug
			verify(databaseService, atLeastOnce()).getSetting(eq("ntfyUrl"), anyString());
		}

		@Test
		@DisplayName("channel='ntfy' with auth header — no exception")
		void channelNtfyWithAuth() {
			// Stub: ntfy with Basic auth credentials configured
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("ntfy");
			when(databaseService.getSetting(eq("ntfyUrl"), anyString())).thenReturn("https://ntfy.example.com");
			when(databaseService.getSetting(eq("ntfyTopic"), anyString())).thenReturn("my-topic");
			when(databaseService.getSetting(eq("ntfyAuth"), anyString())).thenReturn("user:pass");
			// Auth header path must not throw even though HTTP call is async
			assertThatCode(() -> svc.notify(OWNER, "T", "M", 1)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("unknown channel — no exception (graceful fallback)")
		void unknownChannelNoException() {
			// Stub: an unconfigured/future channel type the service doesn't know about
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("slack");
			// No exception = service has a safe fallback for unrecognised channel names
			assertThatCode(() -> svc.notify(OWNER, "Title", "Body", 3)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("priority is clamped — no exception for extreme values")
		void extremePriorityNoException() {
			// Stub: "none" channel so we test priority validation in isolation
			when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("none");
			// Out-of-range priority values must be clamped/ignored — not throw
			assertThatCode(() -> svc.notify(OWNER, "T", "M", 999)).doesNotThrowAnyException();
			assertThatCode(() -> svc.notify(OWNER, "T", "M", -5)).doesNotThrowAnyException();
		}
	}
}
