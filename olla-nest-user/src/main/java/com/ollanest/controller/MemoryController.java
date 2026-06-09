package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.MemoryService;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * REST controller for the long-term memory store: create, list, semantic
 * search, delete, and bulk import/export of remembered facts.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The assistant persists durable facts ("memories") that can be recalled across
 * sessions via semantic search. This controller is the user-facing surface over
 * that store, letting a user inspect, curate, and migrate their own memories.
 * All embedding, storage, and similarity search is delegated to
 * {@link MemoryService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id, so memories never leak across users.</li>
 * <li>{@link #search} performs semantic recall (vector similarity), whereas
 * {@link #list} is a plain recency-bounded enumeration.</li>
 * <li>Manually created memories are tagged with source {@code "user"} to
 * distinguish them from automatically extracted ones.</li>
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
@RequestMapping("/api/memory")
public class MemoryController extends BaseController {

    /** Service backing memory persistence, embedding, and semantic recall. */
    private final MemoryService memoryService;

    /**
     * Constructor-injects the memory service.
     *
     * @param memoryService the service backing all memory operations
     * @since v2026.2.1
     */
    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * Lists the calling user's memories, most recent first.
     *
     * @param req   the HTTP request, used to resolve the authenticated user
     * @param limit maximum number of memories to return (default 100)
     * @return an OK response with the user's memories
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "100") int limit) {
        User user = requireAuth(req);
        return ok(memoryService.list(user.id, limit));
    }

    /**
     * Performs semantic recall over the calling user's memories.
     *
     * @param req   the HTTP request, used to resolve the authenticated user
     * @param q     the natural-language query to match against
     * @param top_k maximum number of best-matching memories to return (default 10)
     * @return an OK response with the most relevant memories
     * @since v2026.2.1
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest req,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int top_k) {
        User user = requireAuth(req);
        return ok(memoryService.recall(user.id, q, top_k));
    }

    /**
     * Stores a new memory for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body request payload; {@code text} is required, optional {@code tags}
     *             and {@code session_id} provide additional context
     * @return a CREATED response with the stored memory, or a 400 if {@code text}
     *         is missing or blank
     * @since v2026.2.1
     */
    @PostMapping
    public ResponseEntity<?> remember(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) {
            return badRequest("text is required");
        }
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.getOrDefault("tags", List.of());
        String sessionId = (String) body.get("session_id");
        var memory = memoryService.remember(user.id, text.trim(), sessionId, "user", tags);
        return created(memory);
    }

    /**
     * Deletes a single memory owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the memory to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> forget(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        memoryService.forget(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Deletes all memories owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response acknowledging the bulk deletion
     * @since v2026.2.1
     */
    @DeleteMapping
    public ResponseEntity<?> forgetAll(HttpServletRequest req) {
        User user = requireAuth(req);
        memoryService.forgetAll(user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Bulk-imports memories for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body request payload; {@code memories} is the list of texts to import
     *             and optional {@code source} labels their origin
     * @return an OK response with the number of memories imported
     * @since v2026.2.1
     */
    @PostMapping("/import")
    public ResponseEntity<?> importMemories(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) body.getOrDefault("memories", List.of());
        String source = (String) body.getOrDefault("source", "import");
        int count = memoryService.importMemories(user.id, texts, source);
        return ok(Map.of("ok", true, "imported", count));
    }

    /**
     * Exports all of the calling user's memories.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response with the exported memories and their total count
     * @since v2026.2.1
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(HttpServletRequest req) {
        User user = requireAuth(req);
        List<Map<String, Object>> memories = memoryService.exportAll(user.id);
        return ok(Map.of("memories", memories, "count", memories.size()));
    }
}
