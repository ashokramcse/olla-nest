package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ollanest.model.User;
import com.ollanest.service.CookbookService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for the model "cookbook": hardware detection, model catalog,
 * and download management.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Helps users pick and provision local models that fit their machine. It
 * detects the host hardware, serves a recommended model catalog, and (for
 * admins) drives model downloads from Hugging Face with live progress streamed
 * over SSE. The heavy lifting — hardware probing, catalog assembly, and
 * download orchestration — lives in {@link CookbookService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Hardware detection and catalog browsing only require authentication;
 * download listing and starting downloads are admin-only because they consume
 * disk and network resources on the host.</li>
 * <li>{@link #download} returns an {@link SseEmitter} with a two-hour timeout
 * so the client can follow long-running downloads in real time.</li>
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
@RequestMapping("/api/cookbook")
public class CookbookController extends BaseController {

	/** Service backing hardware detection, the model catalog, and downloads. */
	private final CookbookService cookbookService;

	/**
	 * Constructor-injects the cookbook service.
	 *
	 * @param cookbookService the service backing all cookbook operations
	 * @since v2026.2.1
	 */
	public CookbookController(CookbookService cookbookService) {
		this.cookbookService = cookbookService;
	}

	/**
	 * Reports detected host hardware (CPU, memory, GPU, etc.).
	 *
	 * @param req the HTTP request; authentication is required
	 * @return an OK response with the detected hardware profile
	 * @since v2026.2.1
	 */
	@GetMapping("/hardware")
	public ResponseEntity<?> hardware(HttpServletRequest req) {
		requireAuth(req);
		return ok(cookbookService.detectHardware());
	}

	/**
	 * Returns the recommended model catalog.
	 *
	 * @param req the HTTP request; authentication is required
	 * @return an OK response with the model catalog
	 * @since v2026.2.1
	 */
	@GetMapping("/catalog")
	public ResponseEntity<?> catalog(HttpServletRequest req) {
		requireAuth(req);
		return ok(cookbookService.getCatalog());
	}

	/**
	 * Lists current and past model downloads (admin only).
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the downloads, or a 403 if the caller is not an
	 *         admin
	 * @since v2026.2.1
	 */
	@GetMapping("/downloads")
	public ResponseEntity<?> downloads(HttpServletRequest req) {
		User user = requireAuth(req);
		if (!"admin".equals(user.role))
			return forbidden("Admin only");
		return ok(cookbookService.getDownloads());
	}

	/**
	 * Starts a model download from Hugging Face, streaming progress over SSE (admin
	 * only).
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body request payload; {@code hf_repo} is required and
	 *             {@code hf_filename} optionally narrows to a single file
	 * @return an {@link SseEmitter} streaming download progress events
	 * @throws ForbiddenException       if the caller is not an admin
	 * @throws IllegalArgumentException if {@code hf_repo} is missing or blank
	 * @since v2026.2.1
	 */
	@PostMapping("/download")
	public SseEmitter download(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		if (!"admin".equals(user.role))
			throw new ForbiddenException("Admin only");

		String hfRepo = (String) body.get("hf_repo");
		String hfFile = (String) body.get("hf_filename");
		if (hfRepo == null || hfRepo.isBlank())
			throw new IllegalArgumentException("hf_repo is required");

		SseEmitter emitter = new SseEmitter(7_200_000L); // 2h timeout
		emitter.onTimeout(emitter::complete);
		cookbookService.startDownload(hfRepo, hfFile, emitter);
		return emitter;
	}

	/**
	 * Builds a 403 Forbidden response body for non-admin access attempts.
	 *
	 * @param msg the human-readable reason
	 * @return a 403 response carrying the error message
	 * @since v2026.2.1
	 */
	private ResponseEntity<?> forbidden(String msg) {
		return ResponseEntity.status(403).body(Map.of("ok", false, "error", msg));
	}
}
