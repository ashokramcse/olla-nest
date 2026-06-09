package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.PersonalAssistantService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for configuring the user's personal AI assistant.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Each user has a personal assistant profile (persona, preferences, and
 * proactive check-in behaviour). This controller exposes reading and updating
 * that profile, plus listing the assistant's scheduled check-ins. State and
 * defaults are managed by {@link PersonalAssistantService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>{@link #get} lazily creates a default profile on first access, so the
 * endpoint never returns "not found".</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController extends BaseController {

	/** Service backing assistant profile state and check-ins. */
	private final PersonalAssistantService assistantService;

	/**
	 * Constructor-injects the personal assistant service.
	 *
	 * @param assistantService the service backing all assistant operations
	 * @since v2026.2.1
	 */
	public AssistantController(PersonalAssistantService assistantService) {
		this.assistantService = assistantService;
	}

	/**
	 * Returns the calling user's assistant profile, creating a default if needed.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the assistant profile
	 * @since v2026.2.1
	 */
	@GetMapping
	public ResponseEntity<?> get(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(assistantService.getOrCreate(user.id));
	}

	/**
	 * Updates the calling user's assistant profile.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the updated profile fields
	 * @return an OK response with the updated profile
	 * @since v2026.2.1
	 */
	@PutMapping
	public ResponseEntity<?> update(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return ok(assistantService.update(user.id, body));
	}

	/**
	 * Lists the assistant's scheduled check-ins for the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the user's check-ins
	 * @since v2026.2.1
	 */
	@GetMapping("/check-ins")
	public ResponseEntity<?> checkIns(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(assistantService.getCheckIns(user.id));
	}
}
