package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;

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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Unit tests for {@link TerminalService}.
 *
 * <p>
 * Covers construction and the connection-closed path when the session is not
 * found in the process map. Real shell processes are never spawned.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TerminalService — unit tests")
class TerminalServiceTest {

	@Mock
	JdbcTemplate db;
	@Mock
	WebSocketSession session;

	@InjectMocks
	TerminalService service;

	@Test
	@DisplayName("service instantiates without throwing")
	void constructionSucceeds() {
		// No shell process spawned during construction — just state initialization
		assertThat(service).isNotNull();
	}

	// ── afterConnectionClosed() ────────────────────────────────────────────────

	@Nested
	@DisplayName("afterConnectionClosed()")
	class AfterConnectionClosed {

		@Test
		@DisplayName("does not throw when session is not in the process map")
		void noThrowForUnknownSession() {
			// Stub: WebSocket session ID that was never in the active process map
			when(session.getId()).thenReturn("ws-session-unknown");
			when(session.getAttributes()).thenReturn(new HashMap<>());
			// Stub audit DB write to succeed silently
			when(db.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

			// Closing a session with no associated process must be a safe no-op
			assertThatCode(() -> service.afterConnectionClosed(session, CloseStatus.NORMAL)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("attempts to write audit event to DB on close")
		void writesAuditEventOnClose() throws Exception {
			// Stub: valid session ID and empty attribute map
			when(session.getId()).thenReturn("ws-session-123");
			when(session.getAttributes()).thenReturn(new HashMap<>());

			service.afterConnectionClosed(session, CloseStatus.NORMAL);

			// Audit event must be written on close for SOC 2 availability logging
			verify(db, atLeastOnce()).update(contains("INSERT INTO audit_events"), any(Object[].class));
		}
	}
}
