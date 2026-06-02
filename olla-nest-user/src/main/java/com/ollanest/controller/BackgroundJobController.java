package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.BackgroundJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Background jobs API — list active jobs, cancel, view history. */
@RestController
@RequestMapping("/api/jobs")
public class BackgroundJobController extends BaseController {

    private final BackgroundJobService jobService;

    public BackgroundJobController(BackgroundJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "20") int limit) {
        User user = requireAuth(req);
        return ok(jobService.listByOwner(user.id, limit));
    }

    @GetMapping("/active")
    public ResponseEntity<?> active(HttpServletRequest req) {
        User user = requireAdminUser(req);
        return ok(jobService.listActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        requireAuth(req);
        var job = jobService.getById(id);
        if (job == null) return notFound("Job not found");
        return ok(job);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String id) {
        requireAuth(req);
        boolean cancelled = jobService.cancel(id);
        return ok(Map.of("ok", true, "cancelled", cancelled));
    }
}
