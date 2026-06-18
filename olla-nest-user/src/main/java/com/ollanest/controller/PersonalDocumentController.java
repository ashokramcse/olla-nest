package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ollanest.model.User;
import com.ollanest.service.PersonalDocumentService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for per-user personal document upload and text extraction.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Lets users upload their own documents (PDF, DOCX, PPTX, XLSX, TXT, MD, CSV,
 * and code files up to 25&nbsp;MB) into private storage with automatic RAG
 * ingestion, so the assistant can answer questions over them. It also exposes a
 * stateless text-extraction endpoint for previewing parsed content. All
 * parsing, storage, and ingestion are delegated to
 * {@link PersonalDocumentService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@link #upload} scopes documents to the authenticated user's id; a
 * rejected file type or size yields a 400 (via
 * {@link IllegalArgumentException}).</li>
 * <li>{@link #extract-text} does not persist anything — it only requires
 * authentication and returns the parsed text.</li>
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
@RequestMapping("/api/documents/personal")
public class PersonalDocumentController extends BaseController {

	/** Service backing document parsing, storage, and RAG ingestion. */
	private final PersonalDocumentService personalDocService;

	/**
	 * Constructor-injects the personal document service.
	 *
	 * @param personalDocService the service backing document operations
	 * @since v2026.2.1
	 */
	public PersonalDocumentController(PersonalDocumentService personalDocService) {
		this.personalDocService = personalDocService;
	}

	/**
	 * Uploads a personal document and ingests it for RAG.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param file the multipart document file
	 * @return a CREATED response with the stored document, a 400 for an
	 *         unsupported/oversized file, or a 500 if processing fails
	 * @since v2026.2.1
	 */
	@PostMapping("/upload")
	public ResponseEntity<?> upload(HttpServletRequest req, @RequestParam MultipartFile file) {
		User user = requireAuth(req);
		try {
			var result = personalDocService.upload(user.id, file.getBytes(), file.getOriginalFilename());
			return created(result);
		} catch (IllegalArgumentException e) {
			return badRequest(e.getMessage());
		} catch (Exception e) {
			return serverError("Upload failed: " + e.getMessage());
		}
	}

	/**
	 * Extracts plain text from an uploaded document without persisting it.
	 *
	 * @param req  the HTTP request; authentication is required
	 * @param file the multipart document file to parse
	 * @return an OK response with the extracted {@code text} and its character
	 *         count, or a 500 if extraction fails
	 * @since v2026.2.1
	 */
	@PostMapping("/extract-text")
	public ResponseEntity<?> extractText(HttpServletRequest req, @RequestParam MultipartFile file) {
		requireAuth(req);
		try {
			String text = personalDocService.extractText(file.getBytes(), file.getOriginalFilename());
			return ok(Map.of("text", text != null ? text : "", "chars", text != null ? text.length() : 0));
		} catch (Exception e) {
			return serverError("Text extraction failed: " + e.getMessage());
		}
	}
}
