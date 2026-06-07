package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.TaskSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for scheduled tasks: CRUD, pause/resume, and run history.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users can schedule recurring agent tasks (cron-like jobs). This controller is
 * the management surface over those schedules, including lifecycle transitions
 * (pause/resume) and inspection of past runs. Scheduling, execution, and
 * ownership enforcement are delegated to {@link TaskSchedulerService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>Pause/resume are exposed as dedicated actions but implemented as
 * {@code status} field updates through {@link TaskSchedulerService#update}.</li>
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
@RequestMapping("/api/tasks")
public class TasksController extends BaseController {

    /** Service backing scheduled-task persistence, scheduling, and run history. */
    private final TaskSchedulerService taskService;

    /**
     * Constructor-injects the task scheduler service.
     *
     * @param taskService the service backing all scheduled-task operations
     * @since v2026.2.1
     */
    public TasksController(TaskSchedulerService taskService) {
        this.taskService = taskService;
    }

    /**
     * Lists the calling user's scheduled tasks, optionally filtered by status.
     *
     * @param req    the HTTP request, used to resolve the authenticated user
     * @param status optional lifecycle status filter
     * @return an OK response with the matching tasks
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(required = false) String status) {
        User user = requireAuth(req);
        return ok(taskService.list(user.id, status));
    }

    /**
     * Fetches a single scheduled task owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the task to fetch
     * @return an OK response with the task, or a 404 if it does not exist
     * @since v2026.2.1
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var task = taskService.getById(id, user.id);
        if (task == null) return notFound("Task not found");
        return ok(task);
    }

    /**
     * Creates a new scheduled task for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the task definition (schedule, action, etc.)
     * @return a CREATED response with the persisted task
     * @since v2026.2.1
     */
    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(taskService.create(user.id, body));
    }

    /**
     * Updates an existing scheduled task owned by the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the task to update
     * @param body the updated task fields
     * @return an OK response with the updated task
     * @since v2026.2.1
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, body));
    }

    /**
     * Deletes a scheduled task owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the task to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        taskService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Pauses a scheduled task owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the task to pause
     * @return an OK response with the updated task
     * @since v2026.2.1
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, Map.of("status", "paused")));
    }

    /**
     * Resumes a paused task owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the task to resume
     * @return an OK response with the updated task
     * @since v2026.2.1
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        return ok(taskService.update(id, user.id, Map.of("status", "active")));
    }

    /**
     * Lists recent execution runs of a task owned by the calling user.
     *
     * @param req   the HTTP request, used to resolve the authenticated user
     * @param id    the id of the task whose runs are requested
     * @param limit maximum number of runs to return (default 20)
     * @return an OK response with the task's run history
     * @since v2026.2.1
     */
    @GetMapping("/{id}/runs")
    public ResponseEntity<?> runs(HttpServletRequest req, @PathVariable String id,
            @RequestParam(defaultValue = "20") int limit) {
        User user = requireAuth(req);
        return ok(taskService.getRuns(id, user.id, limit));
    }
}
