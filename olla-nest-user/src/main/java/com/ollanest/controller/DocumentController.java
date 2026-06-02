package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.RagService;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST API for RAG document management.
 *
 * <p>
 * Provides endpoints to list, upload, and delete documents stored in the
 * {@code rag_documents} table. Uploaded documents are ingested by
 * {@link RagService}: text is extracted, chunked, embedded, and stored in
 * {@code rag_chunks} for retrieval during chat.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /api/documents} — list all documents visible to the user</li>
 * <li>{@code POST /api/documents/upload} — upload a PDF or text file (max 10
 * MB)</li>
 * <li>{@code DELETE /api/documents/{id}} — remove a document and its
 * chunks</li>
 * </ul>
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>A 10 MB hard limit is enforced here (before streaming to disk) to prevent
 * memory exhaustion from large uploads.</li>
 * <li>Document IDs are generated from a combination of timestamp and random
 * base-36 string to avoid sequential enumeration.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.2 — introduced with Spring AI 1.0.0 integration
 * @version v2026.1.10 — HIGH-1 IDOR ownership check on delete; HIGH-4 MIME type validation
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController extends BaseController {

	private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

	/** HIGH-5 FIX: Max uploads per user per hour to prevent disk-exhaustion DoS. */
	private static final int UPLOAD_RATE_LIMIT_PER_HOUR = 20;
	/** HIGH-5: Sliding-window rate limit state: userId → [count, windowStartMs]. */
	private final ConcurrentHashMap<String, AtomicLong[]> uploadRateLimitMap = new ConcurrentHashMap<>();

	/** RAG pipeline service for ingest, retrieval, and deletion. */
	private final RagService ragService;

	/** JDBC template for existence checks before deletion. */
	private final JdbcTemplate db;

	/**
	 * Constructor-injects the RAG service and JDBC template.
	 *
	 * @param ragService the RAG pipeline service
	 * @param db         the JDBC template
	 * @since v2026.1.2 — introduced with Spring AI 1.0.0 integration
	 */
	public DocumentController(RagService ragService, JdbcTemplate db) {
		this.ragService = ragService;
		this.db = db;
	}

	/**
	 * Lists all documents stored in the RAG document store.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK with {@code {ok: true, documents: [doc]}}
	 * @since v2026.1.2 — introduced with Spring AI 1.0.0 integration
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = guardAuth(req);
		if (err != null)
			return err;
		List<Map<String, Object>> docs = ragService.listDocuments();
		return ResponseEntity.ok(Map.of("ok", true, "documents", docs));
	}

	/**
	 * Uploads a document file and ingests it into the RAG chunk store.
	 *
	 * <p>
	 * Accepts PDF ({@code application/pdf}) and plain text files. The file is
	 * text-extracted, split into ~1800-character overlapping chunks, embedded via
	 * Ollama, and stored in {@code rag_chunks}. The document record is returned in
	 * the response.
	 *
	 * @param file  the multipart file to upload; must not be empty and must be ≤ 10
	 *              MB
	 * @param scope visibility scope for the document: {@code "global"} (visible to
	 *              all users) or {@code "user"} (current user only)
	 * @param req   the current HTTP request (for auth and CSRF check)
	 * @return 200 OK with {@code {ok: true, document: doc}} on success; 400 for
	 *         empty or oversized files; 500 on processing error
	 * @since v2026.1.2 — introduced with Spring AI 1.0.0 integration
	 */
	@PostMapping("/upload")
	public ResponseEntity<Map<String, Object>> upload(@RequestParam MultipartFile file,
			@RequestParam(defaultValue = "global") String scope, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
		if (err != null)
			return err;
		User user = getUser(req);

		// HIGH-5 FIX: Per-user upload rate limit (20 uploads per hour)
		if (!checkUploadRateLimit(user.id)) {
			return ResponseEntity.status(429).body(Map.of("ok", false,
				"error", "Upload rate limit reached (max " + UPLOAD_RATE_LIMIT_PER_HOUR + " per hour)"));
		}

		if (file.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "File is empty"));
		}
		if (file.getSize() > 10 * 1024 * 1024) {
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "File too large (max 10 MB)"));
		}

		// HIGH-4 FIX: Validate by magic bytes AND extension — do NOT trust client MIME type.
		// application/octet-stream is a generic catch-all that bypasses whitelist intent.
		String fname = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase().trim() : "";
		boolean allowedExt = fname.endsWith(".pdf") || fname.endsWith(".txt") || fname.endsWith(".md");
		if (!allowedExt) {
			return ResponseEntity.badRequest().body(Map.of("ok", false,
				"error", "Unsupported file type. Allowed extensions: .pdf, .txt, .md"));
		}
		// Magic byte validation: verify the file content matches its declared extension
		String resolvedType;
		try {
			resolvedType = validateMagicBytes(file, fname);
		} catch (IOException e) {
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Could not read file: " + e.getMessage()));
		}
		if (resolvedType == null) {
			return ResponseEntity.badRequest().body(Map.of("ok", false,
				"error", "File content does not match its extension (magic byte mismatch)"));
		}

		String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
		// LOW-7 FIX: use SecureRandom for document ID
		java.security.SecureRandom rng = new java.security.SecureRandom();
		String docId = "doc-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString(rng.nextLong() & Long.MAX_VALUE, 36);

		try {
			Map<String, Object> result = ragService.ingestDocument(docId, name, resolvedType, file.getSize(),
					file.getInputStream(), user.name, scope);
			return ResponseEntity.ok(Map.of("ok", true, "document", result));
		} catch (Exception e) {
			log.error("[documents] Upload failed for user {}: {}", user.id, e.getMessage());
			return ResponseEntity.status(500).body(Map.of("ok", false, "error", "Upload failed: " + e.getMessage()));
		}
	}

	/**
	 * Deletes a RAG document and all of its associated chunks.
	 *
	 * <p>
	 * Returns 404 if the document ID does not exist in the {@code rag_documents}
	 * table. Deletion is delegated to {@link RagService#deleteDocument(String)}.
	 *
	 * @param id  the document ID to delete
	 * @param req the current HTTP request (for auth and CSRF check)
	 * @return 200 OK {@code {ok: true}}, or 404 if the document is not found
	 * @since v2026.1.2 — introduced with Spring AI 1.0.0 integration
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
		if (err != null)
			return err;
		User user = getUser(req);
		List<Map<String, Object>> docs = db.queryForList(
			"SELECT id, uploaded_by FROM rag_documents WHERE id = ?", id);
		if (docs.isEmpty()) {
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Document not found"));
		}
		// Admins can delete any doc; regular users can only delete their own
		String uploadedBy = (String) docs.get(0).get("uploaded_by");
		if (!"admin".equals(user.role) && !user.name.equals(uploadedBy) && !user.id.equals(uploadedBy)) {
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", "You can only delete your own documents"));
		}
		ragService.deleteDocument(id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	/**
	 * HIGH-5: Sliding-window per-user upload rate limit.
	 * Returns true if the user is within the limit; false if they should be rejected.
	 */
	private boolean checkUploadRateLimit(String userId) {
		long now = System.currentTimeMillis();
		long windowMs = 60 * 60 * 1000L; // 1 hour
		long[] allowed = new long[1];
		uploadRateLimitMap.compute(userId, (k, entry) -> {
			if (entry == null) {
				entry = new AtomicLong[]{new AtomicLong(0), new AtomicLong(now)};
			}
			if (entry[1].get() < now - windowMs) {
				entry[0].set(0);
				entry[1].set(now);
			}
			if (entry[0].get() < UPLOAD_RATE_LIMIT_PER_HOUR) {
				entry[0].incrementAndGet();
				allowed[0] = 1;
			}
			return entry;
		});
		return allowed[0] == 1;
	}

	/** PDF magic bytes: %PDF */
	private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};

	/**
	 * HIGH-4: Validates that the uploaded file's magic bytes match its extension.
	 * Returns the resolved MIME type, or null if the content does not match.
	 */
	private String validateMagicBytes(MultipartFile file, String lowerFilename) throws IOException {
		byte[] header = new byte[8];
		try (InputStream is = file.getInputStream()) {
			int read = is.read(header);
			if (read < 4) return null;
		}
		if (lowerFilename.endsWith(".pdf")) {
			// Must start with %PDF
			for (int i = 0; i < PDF_MAGIC.length; i++) {
				if (header[i] != PDF_MAGIC[i]) return null;
			}
			return "application/pdf";
		}
		if (lowerFilename.endsWith(".txt") || lowerFilename.endsWith(".md")) {
			// Text files: reject binary signatures (PDF, PK/ZIP, ELF, PE)
			boolean isBinary = (header[0] == 0x25 && header[1] == 0x50) // %PDF
				|| (header[0] == 0x50 && header[1] == 0x4B)             // PK zip
				|| (header[0] == 0x7F && header[1] == 0x45)             // ELF
				|| (header[0] == 0x4D && header[1] == 0x5A);            // MZ/PE
			if (isBinary) return null;
			return lowerFilename.endsWith(".md") ? "text/markdown" : "text/plain";
		}
		return null; // unknown extension — caller rejects
	}
}
