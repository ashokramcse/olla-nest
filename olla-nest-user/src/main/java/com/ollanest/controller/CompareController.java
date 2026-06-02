package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.CompareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Blind model A/B comparison API. */
@RestController
@RequestMapping("/api/compare")
public class CompareController extends BaseController {

    private final CompareService compareService;

    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(compareService.create(user.id, body));
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<?> vote(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String winner = (String) body.get("winner");
        if (winner == null || winner.isBlank()) return badRequest("winner is required");
        return ok(compareService.vote(id, user.id, winner));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest req,
            @RequestParam(defaultValue = "20") int limit) {
        User user = requireAuth(req);
        return ok(compareService.list(user.id, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var cmp = compareService.getById(id, user.id);
        if (cmp == null) return notFound("Comparison not found");
        return ok(cmp);
    }
}
