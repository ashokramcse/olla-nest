package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.NotesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for Google Keep-style notes and checklists.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Provides the user-facing CRUD surface over personal notes, including pinning,
 * archiving, and label-based filtering. All persistence and ownership
 * enforcement is delegated to {@link NotesService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id, so notes are isolated per user.</li>
 * <li>Pin and archive are exposed as dedicated POST actions but are implemented
 * as targeted field updates through {@link NotesService#update}.</li>
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
@RequestMapping("/api/notes")
public class NotesController extends BaseController {

    /** Service backing note persistence and ownership checks. */
    private final NotesService notesService;

    /**
     * Constructor-injects the notes service.
     *
     * @param notesService the service backing all note operations
     * @since v2026.2.1
     */
    public NotesController(NotesService notesService) {
        this.notesService = notesService;
    }

    /**
     * Lists the calling user's notes, optionally filtered by archive state and label.
     *
     * @param req      the HTTP request, used to resolve the authenticated user
     * @param archived whether to return archived notes instead of active ones
     * @param label    optional label to filter by
     * @return an OK response with the matching notes
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) String label) {
        User user = requireAuth(req);
        return ok(notesService.list(user.id, archived, label));
    }

    /**
     * Fetches a single note owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the note to fetch
     * @return an OK response with the note, or a 404 if it does not exist
     * @since v2026.2.1
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var note = notesService.getById(id, user.id);
        if (note == null) return notFound("Note not found");
        return ok(note);
    }

    /**
     * Creates a new note for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the note definition
     * @return a CREATED response with the persisted note
     * @since v2026.2.1
     */
    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(notesService.create(user.id, body));
    }

    /**
     * Updates an existing note owned by the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the note to update
     * @param body the updated note fields
     * @return an OK response with the updated note
     * @since v2026.2.1
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(notesService.update(id, user.id, body));
    }

    /**
     * Deletes a note owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the note to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        notesService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Sets or clears the pinned flag on a note owned by the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the note to pin or unpin
     * @param body request payload; {@code pinned} carries the desired state
     * @return an OK response with the updated note
     * @since v2026.2.1
     */
    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pin(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        return ok(notesService.update(id, user.id, Map.of("pinned", pinned)));
    }

    /**
     * Archives a note owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the note to archive
     * @return an OK response with the updated note
     * @since v2026.2.1
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archive(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(notesService.update(id, user.id, Map.of("archived", true)));
    }
}
