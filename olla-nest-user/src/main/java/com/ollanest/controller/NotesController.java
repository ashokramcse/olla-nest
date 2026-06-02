package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.NotesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Notes API — Google Keep-style notes and checklists. */
@RestController
@RequestMapping("/api/notes")
public class NotesController extends BaseController {

    private final NotesService notesService;

    public NotesController(NotesService notesService) {
        this.notesService = notesService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) String label) {
        User user = requireAuth(req);
        return ok(notesService.list(user.id, archived, label));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var note = notesService.getById(id, user.id);
        if (note == null) return notFound("Note not found");
        return ok(note);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(notesService.create(user.id, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(notesService.update(id, user.id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        notesService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pin(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        return ok(notesService.update(id, user.id, Map.of("pinned", pinned)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archive(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(notesService.update(id, user.id, Map.of("archived", true)));
    }
}
