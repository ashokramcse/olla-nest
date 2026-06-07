package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.SessionEnhancementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for chat-session enhancement operations: forking, truncating,
 * and topic analysis.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Beyond plain messaging, users sometimes want to branch a conversation at a
 * point ("fork"), prune its tail ("truncate"), or have the system summarise the
 * topics it covered. This controller groups those history-manipulation actions,
 * delegating the work to {@link SessionEnhancementService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>{@link #analyzeTopics} kicks off analysis asynchronously and returns
 * immediately, so the caller is not blocked on the model.</li>
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
@RequestMapping("/api/sessions")
public class SessionEnhancementController extends BaseController {

    /** Service backing session forking, truncation, and topic analysis. */
    private final SessionEnhancementService enhancementService;

    /**
     * Constructor-injects the session enhancement service.
     *
     * @param enhancementService the service backing all enhancement operations
     * @since v2026.2.1
     */
    public SessionEnhancementController(SessionEnhancementService enhancementService) {
        this.enhancementService = enhancementService;
    }

    /**
     * Forks a session into a new branch at the given message index.
     *
     * @param req       the HTTP request, used to resolve the authenticated user
     * @param sessionId the session to fork
     * @param body      request payload; {@code message_index} is the branch point
     *                  (default 10)
     * @return a CREATED response with the forked session
     * @since v2026.2.1
     */
    @PostMapping("/{sessionId}/fork")
    public ResponseEntity<?> fork(HttpServletRequest req, @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        int messageIndex = ((Number) body.getOrDefault("message_index", 10)).intValue();
        return created(enhancementService.forkSession(sessionId, user.id, messageIndex));
    }

    /**
     * Truncates a session's history from a given message onward.
     *
     * @param req       the HTTP request, used to resolve the authenticated user
     * @param sessionId the session to truncate
     * @param body      request payload; {@code from_message_id} is required and
     *                  marks the first message to remove
     * @return an OK response acknowledging the truncation, or a 400 if
     *         {@code from_message_id} is missing
     * @since v2026.2.1
     */
    @PostMapping("/{sessionId}/truncate")
    public ResponseEntity<?> truncate(HttpServletRequest req, @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String fromMessageId = (String) body.get("from_message_id");
        if (fromMessageId == null) return badRequest("from_message_id is required");
        enhancementService.truncateHistory(sessionId, user.id, fromMessageId);
        return ok(Map.of("ok", true));
    }

    /**
     * Starts asynchronous topic analysis of a session.
     *
     * @param req       the HTTP request, used to resolve the authenticated user
     * @param sessionId the session to analyze
     * @return an OK response confirming analysis has started
     * @since v2026.2.1
     */
    @PostMapping("/{sessionId}/analyze-topics")
    public ResponseEntity<?> analyzeTopics(HttpServletRequest req, @PathVariable String sessionId) {
        User user = requireAuth(req);
        enhancementService.analyzeTopicsAsync(sessionId, user.id);
        return ok(Map.of("ok", true, "message", "Topic analysis started"));
    }
}
