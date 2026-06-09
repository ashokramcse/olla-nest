package com.ollanest.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.ApiTokenService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for managing per-user API bearer tokens used to authenticate
 * programmatic access to the API.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need long-lived credentials to call the API from scripts and
 * integrations without going through the interactive session login flow. This
 * controller lets an authenticated user mint, enumerate, and revoke their own
 * scoped tokens, delegating all persistence and hashing to
 * {@link ApiTokenService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes the operation to that user's id, so tokens are never visible or
 * revocable across users.</li>
 * <li>The plaintext token value is returned exactly once, from {@link #mint},
 * and is never persisted in retrievable form — {@link #list} returns only
 * metadata.</li>
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
@RequestMapping("/api/tokens")
public class ApiTokenController extends BaseController {

	/** Service handling token generation, hashing, persistence, and revocation. */
	private final ApiTokenService tokenService;

	/**
	 * Constructor-injects the token service.
	 *
	 * @param tokenService the service backing all token operations
	 * @since v2026.2.1
	 */
	public ApiTokenController(ApiTokenService tokenService) {
		this.tokenService = tokenService;
	}

	/**
	 * Lists the calling user's tokens (metadata only, no secret values).
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the user's token metadata
	 * @since v2026.2.1
	 */
	@GetMapping
	public ResponseEntity<?> list(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(tokenService.list(user.id));
	}

	/**
	 * Mints a new token for the calling user.
	 *
	 * <p>
	 * The response contains the full plaintext token, which is shown only once and
	 * cannot be retrieved again afterwards.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body request payload; {@code name} labels the token and optional
	 *             {@code scopes} restricts its capabilities (defaults to
	 *             {@code ["chat"]})
	 * @return a CREATED response containing the newly minted token
	 * @since v2026.2.1
	 */
	@PostMapping
	public ResponseEntity<?> mint(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		String name = (String) body.get("name");
		@SuppressWarnings("unchecked")
		List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of("chat"));
		// Full token is returned once in the response
		return created(tokenService.mint(user.id, name, scopes));
	}

	/**
	 * Revokes a single token belonging to the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the token to revoke
	 * @return an OK response acknowledging the revocation
	 * @since v2026.2.1
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> revoke(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		tokenService.revoke(id, user.id);
		return ok(Map.of("ok", true));
	}

	/**
	 * Revokes all tokens belonging to the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response acknowledging the bulk revocation
	 * @since v2026.2.1
	 */
	@DeleteMapping
	public ResponseEntity<?> revokeAll(HttpServletRequest req) {
		User user = requireAuth(req);
		tokenService.revokeAll(user.id);
		return ok(Map.of("ok", true));
	}
}
