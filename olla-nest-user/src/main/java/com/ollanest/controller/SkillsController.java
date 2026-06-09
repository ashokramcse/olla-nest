package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.SkillsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for managing agent "skills": create, update, delete, search,
 * and usage tracking.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Skills are reusable capability definitions the agent can invoke. This
 * controller is the user-facing surface for curating a personal skill library,
 * including semantic search to surface relevant skills. Persistence, embedding,
 * and ownership enforcement are delegated to {@link SkillsService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Most endpoints resolve the caller via {@link BaseController#requireAuth}
 * and scope operations to that user's id.</li>
 * <li>{@link #recordUse} only requires authentication (not ownership) since it
 * merely increments a usage counter for telemetry.</li>
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
@RequestMapping("/api/skills")
public class SkillsController extends BaseController {

	/** Service backing skill persistence, search, and usage tracking. */
	private final SkillsService skillsService;

	/**
	 * Constructor-injects the skills service.
	 *
	 * @param skillsService the service backing all skill operations
	 * @since v2026.2.1
	 */
	public SkillsController(SkillsService skillsService) {
		this.skillsService = skillsService;
	}

	/**
	 * Lists the calling user's skills, optionally filtered by category and status.
	 *
	 * @param req      the HTTP request, used to resolve the authenticated user
	 * @param category optional category filter
	 * @param status   lifecycle status filter (default {@code "active"})
	 * @param limit    maximum number of skills to return (default 100)
	 * @return an OK response with the matching skills
	 * @since v2026.2.1
	 */
	@GetMapping
	public ResponseEntity<?> list(HttpServletRequest req, @RequestParam(required = false) String category,
			@RequestParam(defaultValue = "active") String status, @RequestParam(defaultValue = "100") int limit) {
		User user = requireAuth(req);
		return ok(skillsService.list(user.id, category, status, limit));
	}

	/**
	 * Performs semantic search over the calling user's skills.
	 *
	 * @param req   the HTTP request, used to resolve the authenticated user
	 * @param q     the natural-language query
	 * @param top_k maximum number of best matches to return (default 10)
	 * @return an OK response with the most relevant skills
	 * @since v2026.2.1
	 */
	@GetMapping("/search")
	public ResponseEntity<?> search(HttpServletRequest req, @RequestParam String q,
			@RequestParam(defaultValue = "10") int top_k) {
		User user = requireAuth(req);
		return ok(skillsService.search(user.id, q, top_k));
	}

	/**
	 * Fetches a single skill owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the skill to fetch
	 * @return an OK response with the skill, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		var skill = skillsService.getById(id, user.id);
		if (skill == null)
			return notFound("Skill not found");
		return ok(skill);
	}

	/**
	 * Creates a new skill for the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the skill definition
	 * @return a CREATED response with the persisted skill
	 * @since v2026.2.1
	 */
	@PostMapping
	public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return created(skillsService.createSkill(body, user.id));
	}

	/**
	 * Updates an existing skill owned by the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param id   the id of the skill to update
	 * @param body the updated skill fields
	 * @return an OK response with the updated skill
	 * @since v2026.2.1
	 */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
			@RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return ok(skillsService.updateSkill(id, body, user.id));
	}

	/**
	 * Deletes a skill owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the skill to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		skillsService.deleteSkill(id, user.id);
		return ok(Map.of("ok", true));
	}

	/**
	 * Records a usage event for a skill (telemetry counter).
	 *
	 * @param req the HTTP request; authentication is required but ownership is not
	 * @param id  the id of the skill that was used
	 * @return an OK response acknowledging the recorded usage
	 * @since v2026.2.1
	 */
	@PostMapping("/{id}/use")
	public ResponseEntity<?> recordUse(HttpServletRequest req, @PathVariable String id) {
		requireAuth(req);
		skillsService.recordUse(id);
		return ok(Map.of("ok", true));
	}
}
