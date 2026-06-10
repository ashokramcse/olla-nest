package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ollanest.model.User;
import com.ollanest.service.MemoryService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link MemoryController} — the user-facing surface over
 * {@link MemoryService} (remember / search / forget).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Pins the {@code text}-required validation on create, owner-scoped delegation
 * for remember/recall/forget (so users cannot touch each other's memories), and
 * that {@code requireAuth} throws for an unauthenticated caller.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link MemoryService} is mocked; verifications assert the owner id is
 * threaded through every delegation.</li>
 * <li>The request is armed as an authenticated user (no CSRF header needed —
 * these endpoints use {@code requireAuth}, not the CSRF guard).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created for the controller test-coverage pass.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryController — remember/search/forget")
class MemoryControllerTest {

	/** Mocked memory service; verified/stubbed per test. */
	@Mock MemoryService memoryService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private MemoryController controller;

	/**
	 * Constructs the controller and arms the request as an authenticated user so
	 * {@code requireAuth} resolves successfully for each test unless it overrides
	 * the user.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		controller = new MemoryController(memoryService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/**
	 * Creating a memory with blank {@code text} is caller error: the controller must
	 * return a 400 and must not call {@code remember}, so no empty memory is stored.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("remember with blank text → 400, nothing remembered")
	void blankTextRejected() {
		ResponseEntity<?> r = controller.remember(req, Map.of("text", "  "));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(memoryService, never()).remember(anyString(), anyString(), any(), anyString(), any());
	}

	/**
	 * A valid create trims the surrounding whitespace from the text and delegates to
	 * {@code remember} scoped to the authenticated owner, returning 201 Created.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("remember with text → remembers for the owner, 201")
	void validCreateRemembers() {
		when(memoryService.remember(eq("u-user-001"), eq("likes pizza"), any(), eq("user"), any()))
				.thenReturn(Map.of("id", "mem-1"));
		ResponseEntity<?> r = controller.remember(req, Map.of("text", " likes pizza "));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(memoryService).remember(eq("u-user-001"), eq("likes pizza"), any(), eq("user"), any());
	}

	/**
	 * Search delegates to {@code recall} scoped to the authenticated owner, so one
	 * user can never recall another user's memories.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("search delegates to recall for the owner")
	void searchDelegates() {
		when(memoryService.recall("u-user-001", "pizza", 10)).thenReturn(List.of());
		ResponseEntity<?> r = controller.search(req, "pizza", 10);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(memoryService).recall("u-user-001", "pizza", 10);
	}

	/**
	 * Forget delegates to the service scoped to the owner, so a user can only delete
	 * their own memory rows (no cross-user delete).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("forget delegates to service for the owner")
	void forgetDelegates() {
		ResponseEntity<?> r = controller.forget(req, "mem-1");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(memoryService).forget("mem-1", "u-user-001");
	}

	/**
	 * An unauthenticated caller trips {@code requireAuth}, which throws
	 * {@link BaseController.AuthException} (mapped to 401 by the global handler at
	 * runtime).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unauthenticated remember throws AuthException")
	void unauthenticatedThrows() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		assertThatThrownBy(() -> controller.remember(req, Map.of("text", "x")))
				.isInstanceOf(BaseController.AuthException.class);
	}
}
