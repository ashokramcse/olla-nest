package com.ollanest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.NoSuchElementException;

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
import com.ollanest.service.EmailService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link EmailController#send}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Guards BUG-039: the send handler must translate a missing account
 * ({@link NoSuchElementException}) into a 404 and invalid input
 * ({@link IllegalArgumentException}) into a 400, instead of collapsing every
 * exception into a 500 the way its previous broad catch did.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link EmailService} is a mock; tests drive it to throw the relevant
 * exception type or succeed.</li>
 * <li>The request is armed with an authenticated user so {@code requireAuth}
 * passes.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created for the BUG-039 fix and EmailController coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailController.send() — error mapping (BUG-039)")
class EmailControllerTest {

	/** Mocked email service; driven per-test to throw or succeed. */
	@Mock EmailService emailService;
	/** Mocked request carrying the authenticated user. */
	@Mock HttpServletRequest req;

	/** Controller under test, constructed with the mocked service. */
	private EmailController controller;

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
		controller = new EmailController(emailService);
		User user = new User();
		user.id = "u-user-001";
		user.role = "user";
		when(req.getAttribute("authenticatedUser")).thenReturn(user);
	}

	/**
	 * Sending from an unknown account must surface as a 404 (the service throws
	 * {@link NoSuchElementException}), not the misleading 500 the old broad catch
	 * produced — the core BUG-039 guard.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("unknown account → 404")
	void unknownAccountIsNotFound() throws Exception {
		doThrow(new NoSuchElementException("Email account not found: nope")).when(emailService).sendEmail(eq("nope"),
				eq("u-user-001"), any());
		ResponseEntity<?> r = controller.send(req, "nope", Map.of("to", "x@y.com", "subject", "s", "body", "b"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * Invalid send input (service throws {@link IllegalArgumentException}) must
	 * surface as a 400, not a 500.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("invalid input → 400")
	void invalidInputIsBadRequest() throws Exception {
		doThrow(new IllegalArgumentException("recipient is required")).when(emailService).sendEmail(anyString(),
				anyString(), any());
		ResponseEntity<?> r = controller.send(req, "acc-1", Map.of());
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * A genuine send failure (e.g. SMTP error) remains a 500 so real faults are not
	 * masked behind the 404/400 mappings.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("SMTP failure → 500")
	void smtpFailureIsServerError() throws Exception {
		doThrow(new RuntimeException("SMTP connect failed")).when(emailService).sendEmail(anyString(), anyString(),
				any());
		ResponseEntity<?> r = controller.send(req, "acc-1", Map.of("to", "x@y.com"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * A successful send returns 200 with {@code ok=true}.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("success → 200")
	void successReturnsOk() throws Exception {
		ResponseEntity<?> r = controller.send(req, "acc-1", Map.of("to", "x@y.com", "subject", "s", "body", "b"));
		assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
	}
}
