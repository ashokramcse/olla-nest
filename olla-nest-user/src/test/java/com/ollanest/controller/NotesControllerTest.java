package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.ollanest.service.NotesService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link NotesController} — the user notes CRUD surface over
 * {@link NotesService}.
 *
 * <p>
 * Verifies caller authentication and that create/update/delete/pin operations
 * delegate to the service scoped to the authenticated owner (no cross-user
 * access).
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotesController — CRUD + pin")
class NotesControllerTest {

	/** Mocked notes service; verified/stubbed per test. */
	@Mock NotesService notesService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private NotesController controller;

	/** Arms the request as an authenticated user so {@code requireAuth} passes. */
	@BeforeEach
	void setUp() {
		controller = new NotesController(notesService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/** Create delegates to the service for the owner and returns 201. */
	@Test
	@DisplayName("create → delegates for owner, 201")
	void createDelegates() {
		when(notesService.create(eq("u-user-001"), any())).thenReturn(Map.of("id", "note-1"));
		ResponseEntity<?> r = controller.create(req, Map.of("title", "t"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(notesService).create(eq("u-user-001"), any());
	}

	/** Update delegates to the service scoped to the owning user, returns 200. */
	@Test
	@DisplayName("update → delegates for owner, 200")
	void updateDelegates() {
		when(notesService.update(eq("note-1"), eq("u-user-001"), any())).thenReturn(Map.of("id", "note-1"));
		ResponseEntity<?> r = controller.update(req, "note-1", Map.of("title", "t2"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(notesService).update(eq("note-1"), eq("u-user-001"), any());
	}

	/** Delete delegates to the service scoped to the owner (no cross-user delete). */
	@Test
	@DisplayName("delete → delegates for owner")
	void deleteDelegates() {
		ResponseEntity<?> r = controller.delete(req, "note-1");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(notesService).delete("note-1", "u-user-001");
	}

	/** Pin maps to an owner-scoped update setting the {@code pinned} flag. */
	@Test
	@DisplayName("pin → delegates to update for owner with pinned flag")
	void pinDelegatesToUpdate() {
		when(notesService.update(eq("note-1"), eq("u-user-001"), any())).thenReturn(Map.of("id", "note-1"));
		ResponseEntity<?> r = controller.pin(req, "note-1", Map.of("pinned", true));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(notesService).update(eq("note-1"), eq("u-user-001"), any());
	}

	/** An unauthenticated caller trips {@code requireAuth}, which throws. */
	@Test
	@DisplayName("unauthenticated create throws AuthException")
	void unauthenticatedThrows() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		assertThatThrownBy(() -> controller.create(req, Map.of("title", "t")))
				.isInstanceOf(BaseController.AuthException.class);
	}
}
