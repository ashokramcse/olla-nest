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
 * {@link MemoryService} (create / search / forget).
 *
 * <p>
 * Verifies caller authentication, the {@code text}-required validation on
 * create, and that read/delete operations delegate to the service scoped to the
 * authenticated owner.
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryController — create/search/forget")
class MemoryControllerTest {

	/** Mocked memory service; verified/stubbed per test. */
	@Mock MemoryService memoryService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private MemoryController controller;

	/** Arms the request as an authenticated user so {@code requireAuth} passes. */
	@BeforeEach
	void setUp() {
		controller = new MemoryController(memoryService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/** Creating a memory with blank {@code text} is a 400 and persists nothing. */
	@Test
	@DisplayName("create with blank text → 400, nothing remembered")
	void blankTextRejected() {
		ResponseEntity<?> r = controller.remember(req, Map.of("text", "  "));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(memoryService, never()).remember(anyString(), anyString(), any(), anyString(), any());
	}

	/** A valid create trims the text and delegates to {@code remember} for the owner. */
	@Test
	@DisplayName("create with text → remembers for the owner, 200")
	void validCreateRemembers() {
		when(memoryService.remember(eq("u-user-001"), eq("likes pizza"), any(), eq("user"), any()))
				.thenReturn(Map.of("id", "mem-1"));
		ResponseEntity<?> r = controller.remember(req, Map.of("text", " likes pizza "));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(memoryService).remember(eq("u-user-001"), eq("likes pizza"), any(), eq("user"), any());
	}

	/** Search delegates to {@code recall} scoped to the authenticated owner. */
	@Test
	@DisplayName("search delegates to recall for the owner")
	void searchDelegates() {
		when(memoryService.recall("u-user-001", "pizza", 10)).thenReturn(List.of());
		ResponseEntity<?> r = controller.search(req, "pizza", 10);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(memoryService).recall("u-user-001", "pizza", 10);
	}

	/** Forget delegates to the service scoped to the owner (no cross-user delete). */
	@Test
	@DisplayName("forget delegates to service for the owner")
	void forgetDelegates() {
		ResponseEntity<?> r = controller.forget(req, "mem-1");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(memoryService).forget("mem-1", "u-user-001");
	}

	/** An unauthenticated caller trips {@code requireAuth}, which throws. */
	@Test
	@DisplayName("unauthenticated create throws AuthException")
	void unauthenticatedThrows() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		assertThatThrownBy(() -> controller.remember(req, Map.of("text", "x")))
				.isInstanceOf(BaseController.AuthException.class);
	}
}
