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
import com.ollanest.service.TaskSchedulerService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link TasksController} — the scheduled-task CRUD surface over
 * {@link TaskSchedulerService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Pins caller authentication and that create/update/delete delegate to the
 * scheduler service scoped to the authenticated owner, so a user's scheduled
 * tasks remain private to them.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link TaskSchedulerService} is mocked; verifications assert the owner
 * id is threaded through every delegation.</li>
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
@DisplayName("TasksController — CRUD")
class TasksControllerTest {

	/** Mocked scheduler service; verified/stubbed per test. */
	@Mock TaskSchedulerService taskService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private TasksController controller;

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
		controller = new TasksController(taskService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/**
	 * Create delegates to the scheduler scoped to the authenticated owner and
	 * returns 201 Created, filing the task under the caller.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("create → delegates for owner, 201")
	void createDelegates() {
		when(taskService.create(eq("u-user-001"), any())).thenReturn(Map.of("id", "task-1"));
		ResponseEntity<?> r = controller.create(req, Map.of("title", "t"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(taskService).create(eq("u-user-001"), any());
	}

	/**
	 * Update delegates to the scheduler scoped to the owning user and returns 200,
	 * so a user cannot modify a task they do not own.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("update → delegates for owner, 200")
	void updateDelegates() {
		when(taskService.update(eq("task-1"), eq("u-user-001"), any())).thenReturn(Map.of("id", "task-1"));
		ResponseEntity<?> r = controller.update(req, "task-1", Map.of("title", "t2"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(taskService).update(eq("task-1"), eq("u-user-001"), any());
	}

	/**
	 * Delete delegates to the scheduler scoped to the owner, so a user can only
	 * delete their own scheduled tasks (no cross-user delete).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("delete → delegates for owner")
	void deleteDelegates() {
		ResponseEntity<?> r = controller.delete(req, "task-1");
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(taskService).delete("task-1", "u-user-001");
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
		assertThatThrownBy(() -> controller.create(req, Map.of("title", "t")))
				.isInstanceOf(BaseController.AuthException.class);
	}
}
