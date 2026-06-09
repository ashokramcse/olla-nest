package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.DeepResearchService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for managing deep-research tasks: listing, cancelling, and
 * retrieving generated reports.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * "Deep research" runs are long-lived agentic tasks that gather sources and
 * synthesise an HTML report. This controller is the management surface over
 * those tasks — it does not start them (that happens elsewhere) but lets users
 * track, cancel, and read the results. Task state and report rendering live in
 * {@link DeepResearchService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Listing and report retrieval scope to the calling user's id; the report
 * endpoint returns rendered {@code text/html} rather than a JSON envelope.</li>
 * <li>A missing report yields a plain 404 so the front-end can distinguish
 * "not ready" from a populated report.</li>
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
@RequestMapping("/api/research")
public class ResearchController extends BaseController {

    /** Service backing research task state and report generation. */
    private final DeepResearchService researchService;

    /**
     * Constructor-injects the deep research service.
     *
     * @param researchService the service backing all research operations
     * @since v2026.2.1
     */
    public ResearchController(DeepResearchService researchService) {
        this.researchService = researchService;
    }

    /**
     * Lists the calling user's research tasks.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response with the user's research tasks
     * @since v2026.2.1
     */
    @GetMapping("/tasks")
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(researchService.listTasks(user.id));
    }

    /**
     * Cancels a running research task.
     *
     * @param req the HTTP request; authentication is required
     * @param id  the id of the task to cancel
     * @return an OK response acknowledging the cancellation
     * @since v2026.2.1
     */
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        // Owner-scoped so a user cannot cancel another user's research task (BUG-022 IDOR).
        researchService.cancel(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Retrieves the rendered HTML report for a research task.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the task whose report is requested
     * @return a {@code text/html} response with the report, or a 404 if no report
     *         is available
     * @since v2026.2.1
     */
    @GetMapping(value = "/tasks/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getReport(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        String html = researchService.getReport(id, user.id);
        if (html == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
