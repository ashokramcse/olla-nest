package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.RagService;

import jakarta.servlet.http.HttpServletRequest;

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

import java.util.List;
import java.util.Map;

/**
 * REST API for RAG document management.
 *
 * <p>Provides endpoints to list, upload, and delete documents stored in the
 * {@code rag_documents} table. Uploaded documents are ingested by
 * {@link RagService}: text is extracted, chunked, embedded, and stored in
 * {@code rag_chunks} for retrieval during chat.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/documents} — list all documents visible to the user</li>
 *   <li>{@code POST /api/documents/upload} — upload a PDF or text file (max 10 MB)</li>
 *   <li>{@code DELETE /api/documents/{id}} — remove a document and its chunks</li>
 * </ul>
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>A 10 MB hard limit is enforced here (before streaming to disk) to prevent
 *       memory exhaustion from large uploads.</li>
 *   <li>Document IDs are generated from a combination of timestamp and random
 *       base-36 string to avoid sequential enumeration.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.2  — introduced with Spring AI 1.0.0 integration
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController extends BaseController {

    /** RAG pipeline service for ingest, retrieval, and deletion. */
    private final RagService ragService;

    /** JDBC template for existence checks before deletion. */
    private final JdbcTemplate db;

    /**
     * Constructor-injects the RAG service and JDBC template.
     *
     * @param  ragService  the RAG pipeline service
     * @param  db          the JDBC template
     * @since   v2026.1.2  — introduced with Spring AI 1.0.0 integration
     */
    public DocumentController(RagService ragService, JdbcTemplate db) {
        this.ragService = ragService;
        this.db = db;
    }

    /**
     * Lists all documents stored in the RAG document store.
     *
     * @param  req  the current HTTP request (for auth check)
     * @return      200 OK with {@code {ok: true, documents: [doc]}}
     * @since   v2026.1.2  — introduced with Spring AI 1.0.0 integration
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuth(req);
        if (err != null) return err;
        List<Map<String, Object>> docs = ragService.listDocuments();
        return ResponseEntity.ok(Map.of("ok", true, "documents", docs));
    }

    /**
     * Uploads a document file and ingests it into the RAG chunk store.
     *
     * <p>Accepts PDF ({@code application/pdf}) and plain text files. The file is
     * text-extracted, split into ~1800-character overlapping chunks, embedded via
     * Ollama, and stored in {@code rag_chunks}. The document record is returned
     * in the response.
     *
     * @param  file   the multipart file to upload; must not be empty and must be
     *                ≤ 10 MB
     * @param  scope  visibility scope for the document: {@code "global"} (visible
     *                to all users) or {@code "user"} (current user only)
     * @param  req    the current HTTP request (for auth and CSRF check)
     * @return        200 OK with {@code {ok: true, document: doc}} on success;
     *                400 for empty or oversized files; 500 on processing error
     * @since   v2026.1.2  — introduced with Spring AI 1.0.0 integration
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "scope", defaultValue = "global") String scope,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;
        User user = getUser(req);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "File too large (max 10 MB)"));
        }

        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String type = file.getContentType() != null ? file.getContentType() : "text/plain";
        String docId = "doc-" + Long.toString(System.currentTimeMillis(), 36) + "-"
                + Long.toString((long) (Math.random() * 36L * 36L * 36L * 36L), 36);

        try {
            Map<String, Object> result = ragService.ingestDocument(
                    docId, name, type, file.getSize(), file.getInputStream(), user.name, scope);
            return ResponseEntity.ok(Map.of("ok", true, "document", result));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Deletes a RAG document and all of its associated chunks.
     *
     * <p>Returns 404 if the document ID does not exist in the {@code rag_documents}
     * table. Deletion is delegated to {@link RagService#deleteDocument(String)}.
     *
     * @param  id   the document ID to delete
     * @param  req  the current HTTP request (for auth and CSRF check)
     * @return      200 OK {@code {ok: true}}, or 404 if the document is not found
     * @since   v2026.1.2  — introduced with Spring AI 1.0.0 integration
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;
        List<Map<String, Object>> docs = db.queryForList(
                "SELECT id FROM rag_documents WHERE id = ?", id);
        if (docs.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Document not found"));
        }
        ragService.deleteDocument(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
