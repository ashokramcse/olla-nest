package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.TaskSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Scheduled Tasks API — CRUD, run history, pause/resume. */
@RestController
@RequestMapping("/api/tasks")
public class TasksController extends BaseController {

    private final TaskSchedulerService taskService;

    public TasksController(TaskSchedulerService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(required = false) String status) {
        User user = requireAuth(req);
        return ok(taskService.list(user.id, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var task = taskService.getById(id, user.id);
        if (task == null) return notFound("Task not found");
        return ok(task);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(taskService.create(user.id, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        taskService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, Map.of("status", "paused")));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, Map.of("status", "active")));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> runs(HttpServletRequest req, @PathVariable String id,
            @RequestParam(defaultValue = "20") int limit) {
        User user = requireAuth(req);
        return ok(taskService.getRuns(id, user.id, limit));
    }
}
