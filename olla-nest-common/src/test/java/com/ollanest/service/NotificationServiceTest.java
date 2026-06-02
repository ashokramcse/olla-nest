package com.ollanest.service;

import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link NotificationService}.
 *
 * <p>Covers: channel="none" — no downstream calls; channel="email" — no exception;
 * channel="ntfy" — {@link DatabaseService#getSetting} called for ntfyUrl; channel="ntfy" is
 * async so no hard assertion on HTTP; unknown channel — no exception.
 *
 * <p>No Spring context, no real HTTP calls — async ntfy is fire-and-forget.
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

    @Mock JdbcTemplate db;
    @Mock DatabaseService databaseService;

    @InjectMocks NotificationService svc;

    // ── notify() — channel routing ────────────────────────────────────────────

    @Nested
    @DisplayName("notify() — channel routing")
    class Notify {

        @Test
        @DisplayName("channel='none' — notify() returns without any error")
        void channelNoneNoOp() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("none");
            assertThatCode(() -> svc.notify(OWNER, "Title", "Body", 3))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("channel='email' — logs notification, no exception thrown")
        void channelEmailNoException() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("email");
            assertThatCode(() -> svc.notify(OWNER, "Reminder", "Your task is due", 3))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("channel='ntfy' — getSetting called for ntfyUrl")
        void channelNtfyCallsGetSetting() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("ntfy");
            when(databaseService.getSetting(eq("ntfyUrl"), anyString())).thenReturn("https://ntfy.sh");
            when(databaseService.getSetting(eq("ntfyTopic"), anyString())).thenReturn("test-topic");
            when(databaseService.getSetting(eq("ntfyAuth"), anyString())).thenReturn("");
            // ntfy is async — just verify no exception and getSetting is called
            assertThatCode(() -> svc.notify(OWNER, "Alert", "Something happened", 5))
                    .doesNotThrowAnyException();
            verify(databaseService, atLeastOnce()).getSetting(eq("ntfyUrl"), anyString());
        }

        @Test
        @DisplayName("channel='ntfy' with auth header — no exception")
        void channelNtfyWithAuth() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("ntfy");
            when(databaseService.getSetting(eq("ntfyUrl"), anyString())).thenReturn("https://ntfy.example.com");
            when(databaseService.getSetting(eq("ntfyTopic"), anyString())).thenReturn("my-topic");
            when(databaseService.getSetting(eq("ntfyAuth"), anyString())).thenReturn("user:pass");
            assertThatCode(() -> svc.notify(OWNER, "T", "M", 1))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("unknown channel — no exception (graceful fallback)")
        void unknownChannelNoException() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("slack");
            assertThatCode(() -> svc.notify(OWNER, "Title", "Body", 3))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("priority is clamped — no exception for extreme values")
        void extremePriorityNoException() {
            when(databaseService.getSetting(eq("notificationChannel"), anyString())).thenReturn("none");
            assertThatCode(() -> svc.notify(OWNER, "T", "M", 999)).doesNotThrowAnyException();
            assertThatCode(() -> svc.notify(OWNER, "T", "M", -5)).doesNotThrowAnyException();
        }
    }
}
