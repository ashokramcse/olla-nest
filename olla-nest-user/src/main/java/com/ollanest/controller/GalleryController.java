package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ollanest.model.User;
import com.ollanest.service.GalleryService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for the image gallery: albums, image upload (with EXIF
 * extraction and deduplication), and editor drafts.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Gives users a place to organise images into albums, upload new images (with
 * automatic EXIF parsing and content-hash deduplication), and persist
 * in-progress editor drafts. All storage, hashing, and metadata extraction are
 * delegated to {@link GalleryService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Endpoints are grouped into albums, images, and editor drafts (marked by
 * the section comments below).</li>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>Uploads that hash to an existing image return a non-error duplicate
 * marker rather than storing a second copy.</li>
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
@RequestMapping("/api/gallery")
public class GalleryController extends BaseController {

	/** Service backing album/image/draft persistence, EXIF, and dedup. */
	private final GalleryService galleryService;

	/**
	 * Constructor-injects the gallery service.
	 *
	 * @param galleryService the service backing all gallery operations
	 * @since v2026.2.1
	 */
	public GalleryController(GalleryService galleryService) {
		this.galleryService = galleryService;
	}

	// ── Albums ────────────────────────────────────────────────────────────────

	/**
	 * Lists the calling user's albums.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the user's albums
	 * @since v2026.2.1
	 */
	@GetMapping("/albums")
	public ResponseEntity<?> listAlbums(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(galleryService.listAlbums(user.id));
	}

	/**
	 * Creates a new album for the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the album definition
	 * @return a CREATED response with the persisted album
	 * @since v2026.2.1
	 */
	@PostMapping("/albums")
	public ResponseEntity<?> createAlbum(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return created(galleryService.createAlbum(user.id, body));
	}

	/**
	 * Deletes an album owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the album to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/albums/{id}")
	public ResponseEntity<?> deleteAlbum(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		galleryService.deleteAlbum(id, user.id);
		return ok(Map.of("ok", true));
	}

	// ── Images ────────────────────────────────────────────────────────────────

	/**
	 * Lists the calling user's images, optionally filtered by album, paged.
	 *
	 * @param req      the HTTP request, used to resolve the authenticated user
	 * @param albumId  optional album to filter by
	 * @param page     1-based page number (default 1)
	 * @param pageSize images per page (default 30)
	 * @return an OK response with the page of images
	 * @since v2026.2.1
	 */
	@GetMapping("/images")
	public ResponseEntity<?> listImages(HttpServletRequest req, @RequestParam(required = false) String albumId,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int pageSize) {
		User user = requireAuth(req);
		return ok(galleryService.listImages(user.id, albumId, page, pageSize));
	}

	/**
	 * Uploads an image for the calling user, optionally into an album.
	 *
	 * <p>
	 * If the uploaded bytes hash to an image the user already has, the existing
	 * image's id is returned with a {@code duplicate} marker instead of storing a
	 * copy.
	 *
	 * @param req     the HTTP request, used to resolve the authenticated user
	 * @param file    the multipart image file
	 * @param albumId optional album to place the image in
	 * @return a CREATED response with the stored image, a duplicate marker if it
	 *         already exists, or a 500 if the upload fails
	 * @since v2026.2.1
	 */
	@PostMapping("/upload")
	public ResponseEntity<?> upload(HttpServletRequest req, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String albumId) {
		User user = requireAuth(req);
		try {
			var result = galleryService.uploadImage(user.id, file.getBytes(), file.getOriginalFilename(), albumId);
			if (Boolean.TRUE.equals(result.get("duplicate"))) {
				return ok(Map.of("ok", false, "duplicate", true, "id", result.get("id")));
			}
			return created(result);
		} catch (Exception e) {
			return serverError("Upload failed: " + e.getMessage());
		}
	}

	/**
	 * Deletes an image owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the image to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/images/{id}")
	public ResponseEntity<?> deleteImage(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		galleryService.deleteImage(id, user.id);
		return ok(Map.of("ok", true));
	}

	// ── Editor Drafts ─────────────────────────────────────────────────────────

	/**
	 * Lists the calling user's saved editor drafts.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the user's drafts
	 * @since v2026.2.1
	 */
	@GetMapping("/drafts")
	public ResponseEntity<?> listDrafts(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(galleryService.listDrafts(user.id));
	}

	/**
	 * Saves (creates or updates) an editor draft for the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the draft contents
	 * @return an OK response with the saved draft
	 * @since v2026.2.1
	 */
	@PostMapping("/drafts")
	public ResponseEntity<?> saveDraft(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return ok(galleryService.saveDraft(user.id, body));
	}

	/**
	 * Fetches a single editor draft owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the draft to fetch
	 * @return an OK response with the draft, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/drafts/{id}")
	public ResponseEntity<?> getDraft(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		var draft = galleryService.getDraft(id, user.id);
		if (draft == null)
			return notFound("Draft not found");
		return ok(draft);
	}

	/**
	 * Deletes an editor draft owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the draft to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/drafts/{id}")
	public ResponseEntity<?> deleteDraft(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		galleryService.deleteDraft(id, user.id);
		return ok(Map.of("ok", true));
	}
}
