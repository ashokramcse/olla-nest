package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.CompareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for blind A/B model comparisons.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Lets a user run the same prompt against two models, then vote for the better
 * response without seeing which model produced which answer. The accumulated
 * votes feed quality comparisons. All session state and tallying is delegated to
 * {@link CompareService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>The model identities behind each side stay hidden until a vote is cast, to
 * keep the comparison blind.</li>
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
@RequestMapping("/api/compare")
public class CompareController extends BaseController {

    /** Service backing comparison creation, voting, and history. */
    private final CompareService compareService;

    /**
     * Constructor-injects the compare service.
     *
     * @param compareService the service backing all comparison operations
     * @since v2026.2.1
     */
    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    /**
     * Starts a new blind comparison for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the comparison configuration (prompt, candidate models, etc.)
     * @return a CREATED response with the new comparison session
     * @since v2026.2.1
     */
    @PostMapping("/start")
    public ResponseEntity<?> start(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(compareService.create(user.id, body));
    }

    /**
     * Records the calling user's vote for the winning side of a comparison.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the comparison being voted on
     * @param body request payload; {@code winner} identifies the chosen side
     * @return an OK response with the updated comparison, or a 400 if
     *         {@code winner} is missing or blank
     * @since v2026.2.1
     */
    @PostMapping("/{id}/vote")
    public ResponseEntity<?> vote(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String winner = (String) body.get("winner");
        if (winner == null || winner.isBlank()) return badRequest("winner is required");
        return ok(compareService.vote(id, user.id, winner));
    }

    /**
     * Lists the calling user's recent comparisons.
     *
     * @param req   the HTTP request, used to resolve the authenticated user
     * @param limit maximum number of comparisons to return (default 20)
     * @return an OK response with the comparison history
     * @since v2026.2.1
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest req,
            @RequestParam(defaultValue = "20") int limit) {
        User user = requireAuth(req);
        return ok(compareService.list(user.id, limit));
    }

    /**
     * Fetches a single comparison owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the comparison to fetch
     * @return an OK response with the comparison, or a 404 if it does not exist
     * @since v2026.2.1
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var cmp = compareService.getById(id, user.id);
        if (cmp == null) return notFound("Comparison not found");
        return ok(cmp);
    }
}
