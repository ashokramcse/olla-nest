package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ImageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * REST API for AI image generation.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Exposes a single unified endpoint for text-to-image generation that delegates
 * to the configured image provider (OpenAI DALL-E, Stable Diffusion via Ollama,
 * or any other provider supported by {@link ImageGenerationService}). The
 * controller handles auth, input validation, activity logging, and error
 * normalisation so that calling code always receives a consistent JSON
 * envelope.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@code provider} field in the request body is optional; when absent
 * the service auto-selects the best available provider.</li>
 * <li>All generation attempts — successful or not — are written to the
 * {@code image_generation_log} table for audit and cost tracking.</li>
 * <li>Responses carry either a remote {@code imageUrl} (for DALL-E) or a
 * {@code base64} data string (for local providers), never both.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.4 — added provider auto-selection and base64 response
 * support</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@RestController
@RequestMapping("/api/images")
public class ImageController extends BaseController {

	/** Delegates the actual image generation call to the configured provider. */
	private final ImageGenerationService imageService;

	/** JDBC template for writing generation log rows. */
	private final JdbcTemplate db;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param imageService the image generation service
	 * @param db           the JDBC template for activity logging
	 * @since v2026.1.0
	 */
	public ImageController(ImageGenerationService imageService, JdbcTemplate db) {
		this.imageService = imageService;
		this.db = db;
	}

	/**
	 * Generates an image from a text prompt.
	 *
	 * <p>
	 * Endpoint: {@code POST /api/images/generate}
	 *
	 * <p>
	 * Processing steps:
	 * <ol>
	 * <li>Authenticate the caller; return 401 if no valid session is present.</li>
	 * <li>Validate that {@code prompt} is non-blank; return 400 otherwise.</li>
	 * <li>Delegate to {@link ImageGenerationService#generate(String, String)} with
	 * the optional {@code provider} hint.</li>
	 * <li>Write a success or error row to {@code image_generation_log}.</li>
	 * <li>Return the image as either a remote URL or a base64 data string.</li>
	 * </ol>
	 *
	 * <p>
	 * The response body always contains {@code ok} and {@code provider}. On success
	 * it additionally contains either {@code imageUrl} (for DALL-E / remote
	 * providers) or {@code base64} (for local providers), but never both.
	 *
	 * @param body JSON request body with:
	 *             <ul>
	 *             <li>{@code prompt} (String, required) — the image
	 *             description</li>
	 *             <li>{@code provider} (String, optional) — preferred provider ID;
	 *             omit to let the service auto-select</li>
	 *             </ul>
	 * @param req  the current HTTP request (session cookie used for auth)
	 * @return 200 OK with {@code {ok: true, provider, imageUrl?|base64?}} on
	 *         success; 400 if {@code prompt} is blank; 401 if the caller is not
	 *         authenticated; 500 with {@code {ok: false, error}} on provider error
	 * @throws no checked exceptions — provider errors are caught and normalised
	 * @since v2026.1.0
	 */
	@PostMapping("/generate")
	public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = guardAuth(req);
		if (err != null)
			return err;
		User user = getUser(req);

		String prompt = (String) body.getOrDefault("prompt", "");
		String provider = (String) body.getOrDefault("provider", null);

		if (prompt.isBlank())
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "prompt is required"));

		String logId = "ig-" + Long.toString(System.currentTimeMillis(), 36);
		try {
			ImageGenerationService.ImageResult result = imageService.generate(prompt, provider);

			// Log the generation
			db.update(
					"INSERT INTO image_generation_log (id, user_id, prompt, provider, model, status, created_at) VALUES (?,?,?,?,?,?,?)",
					logId, user.id, prompt, result.provider(), result.model(), "ok", Instant.now().toString());

			var response = new java.util.LinkedHashMap<String, Object>();
			response.put("ok", true);
			response.put("provider", result.provider());
			if (result.url() != null)
				response.put("imageUrl", result.url());
			if (result.base64() != null)
				response.put("base64", result.base64());
			return ResponseEntity.ok(response);

		} catch (Exception e) {
			db.update(
					"INSERT INTO image_generation_log (id, user_id, prompt, provider, model, status, error, created_at) VALUES (?,?,?,?,?,?,?,?)",
					logId, user.id, prompt, provider != null ? provider : "dalle", null, "error", e.getMessage(),
					Instant.now().toString());
			return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
		}
	}
}
