package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.BackgroundJobService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for inspecting and cancelling background jobs.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Long-running work (downloads, ingestion, agent runs) executes as asynchronous
 * background jobs. This controller lets a user view their own job history,
 * inspect a job's status, and cancel a running job; admins can additionally see
 * the system-wide set of active jobs. Job lifecycle and bookkeeping live in
 * {@link BackgroundJobService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@link #list} is scoped to the calling user; {@link #active} is
 * admin-only because it exposes jobs across all users.</li>
 * <li>{@link #cancel} reports whether the cancellation actually took effect (a
 * job may already have completed).</li>
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
@RequestMapping("/api/jobs")
public class BackgroundJobController extends BaseController {

	/** Service tracking background job state and lifecycle. */
	private final BackgroundJobService jobService;

	/**
	 * Constructor-injects the background job service.
	 *
	 * @param jobService the service backing all job operations
	 * @since v2026.2.1
	 */
	public BackgroundJobController(BackgroundJobService jobService) {
		this.jobService = jobService;
	}

	/**
	 * Lists the calling user's recent background jobs.
	 *
	 * @param req   the HTTP request, used to resolve the authenticated user
	 * @param limit maximum number of jobs to return (default 20)
	 * @return an OK response with the user's jobs
	 * @since v2026.2.1
	 */
	@GetMapping
	public ResponseEntity<?> list(HttpServletRequest req, @RequestParam(defaultValue = "20") int limit) {
		User user = requireAuth(req);
		return ok(jobService.listByOwner(user.id, limit));
	}

	/**
	 * Lists all currently active jobs across users (admin only).
	 *
	 * @param req the HTTP request; must resolve to an admin user
	 * @return an OK response with the active jobs
	 * @since v2026.2.1
	 */
	@GetMapping("/active")
	public ResponseEntity<?> active(HttpServletRequest req) {
		User user = requireAdminUser(req);
		return ok(jobService.listActive());
	}

	/**
	 * Fetches a single job by id.
	 *
	 * @param req the HTTP request; authentication is required
	 * @param id  the id of the job to fetch
	 * @return an OK response with the job, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
		requireAuth(req);
		var job = jobService.getById(id);
		if (job == null)
			return notFound("Job not found");
		return ok(job);
	}

	/**
	 * Requests cancellation of a running job.
	 *
	 * @param req the HTTP request; authentication is required
	 * @param id  the id of the job to cancel
	 * @return an OK response whose {@code cancelled} flag indicates whether the
	 *         cancellation took effect
	 * @since v2026.2.1
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String id) {
		requireAuth(req);
		boolean cancelled = jobService.cancel(id);
		return ok(Map.of("ok", true, "cancelled", cancelled));
	}
}
