package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
import com.ollanest.service.CompareService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link CompareController} — the A/B model comparison surface
 * over {@link CompareService} (start / vote).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Pins the {@code winner}-required validation on vote and confirms that start
 * and vote delegate to the service scoped to the authenticated owner.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link CompareService} is mocked; verifications assert the owner id is
 * threaded through every delegation.</li>
 * <li>The request is armed as an authenticated user.</li>
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
@DisplayName("CompareController — start/vote")
class CompareControllerTest {

	/** Mocked comparison service; verified/stubbed per test. */
	@Mock CompareService compareService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private CompareController controller;

	/**
	 * Constructs the controller and arms the request as an authenticated user so
	 * {@code requireAuth} resolves successfully for each test.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		controller = new CompareController(compareService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/**
	 * Voting without a {@code winner} field is caller error: the controller must
	 * return a 400 and must not record a vote.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("vote without winner → 400, no vote recorded")
	void voteWithoutWinnerRejected() {
		ResponseEntity<?> r = controller.vote(req, "cmp-1", Map.of());
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(compareService, never()).vote(anyString(), anyString(), anyString());
	}

	/**
	 * A vote carrying a {@code winner} delegates to {@code vote} scoped to the
	 * authenticated owner and returns 200.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("vote with winner → delegates to service for the owner")
	void voteDelegates() {
		when(compareService.vote("cmp-1", "u-user-001", "a")).thenReturn(Map.of("winner", "a"));
		ResponseEntity<?> r = controller.vote(req, "cmp-1", Map.of("winner", "a"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(compareService).vote("cmp-1", "u-user-001", "a");
	}

	/**
	 * Starting a comparison delegates to {@code create} scoped to the authenticated
	 * owner and returns 201 Created.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("start delegates to service for the owner")
	void startDelegates() {
		Map<String, Object> body = Map.of("prompt", "hi", "model_a", "a", "model_b", "b");
		when(compareService.create("u-user-001", body)).thenReturn(Map.of("id", "cmp-1"));
		ResponseEntity<?> r = controller.start(req, body);
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(compareService).create("u-user-001", body);
	}
}
