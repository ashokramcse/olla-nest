package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.ollanest.service.ContactsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link ContactsController} — the contacts CRUD + search surface
 * over {@link ContactsService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Pins caller authentication and that search/create/update/delete all delegate
 * to the service scoped to the authenticated owner, so contacts are never shared
 * or mutated across users.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link ContactsService} is mocked; verifications assert the owner id is
 * threaded through every delegation.</li>
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
@DisplayName("ContactsController — CRUD + search")
class ContactsControllerTest {

	/** Mocked contacts service; verified/stubbed per test. */
	@Mock ContactsService contactsService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private ContactsController controller;

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
		controller = new ContactsController(contactsService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/**
	 * Search delegates to the service scoped to the authenticated owner, so a user
	 * only ever searches their own contacts.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("search → delegates for owner")
	void searchDelegates() {
		when(contactsService.search("u-user-001", "jane")).thenReturn(List.of());
		ResponseEntity<?> r = controller.search(req, "jane");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(contactsService).search("u-user-001", "jane");
	}

	/**
	 * Create delegates to the service scoped to the owner and returns 201 Created,
	 * filing the contact under the caller.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("create → delegates for owner, 201")
	void createDelegates() {
		when(contactsService.create(eq("u-user-001"), any())).thenReturn(Map.of("id", "cnt-1"));
		ResponseEntity<?> r = controller.create(req, Map.of("display_name", "Jane"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(contactsService).create(eq("u-user-001"), any());
	}

	/**
	 * Update delegates to the service scoped to the owning user and returns 200, so
	 * a user cannot update a contact they do not own.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("update → delegates for owner, 200")
	void updateDelegates() {
		when(contactsService.update(eq("cnt-1"), eq("u-user-001"), any())).thenReturn(Map.of("id", "cnt-1"));
		ResponseEntity<?> r = controller.update(req, "cnt-1", Map.of("display_name", "Jane Q"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(contactsService).update(eq("cnt-1"), eq("u-user-001"), any());
	}

	/**
	 * Delete delegates to the service scoped to the owner, so a user can only delete
	 * their own contacts (no cross-user delete).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("delete → delegates for owner")
	void deleteDelegates() {
		ResponseEntity<?> r = controller.delete(req, "cnt-1");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(contactsService).delete("cnt-1", "u-user-001");
	}

	/**
	 * An unauthenticated caller trips {@code requireAuth}, which throws
	 * {@link BaseController.AuthException} (mapped to 401 at runtime).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unauthenticated create throws AuthException")
	void unauthenticatedThrows() {
		when(req.getAttribute("authenticatedUser")).thenReturn(null);
		assertThatThrownBy(() -> controller.create(req, Map.of("display_name", "X")))
				.isInstanceOf(BaseController.AuthException.class);
	}
}
