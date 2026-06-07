package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for managing outgoing webhooks.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Lets users register HTTP endpoints that the system calls when events occur,
 * plus enable/disable and test those registrations. Delivery, signing, and
 * persistence are delegated to {@link WebhookService}; this controller is purely
 * the management surface.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>Enable, disable, and test are exposed as dedicated POST actions for
 * clarity; enable/disable both funnel through {@link WebhookService#setEnabled}.</li>
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
@RequestMapping("/api/webhooks")
public class WebhookController extends BaseController {

    /** Service backing webhook persistence, delivery, and testing. */
    private final WebhookService webhookService;

    /**
     * Constructor-injects the webhook service.
     *
     * @param webhookService the service backing all webhook operations
     * @since v2026.2.1
     */
    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Lists the calling user's webhooks.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response with the user's webhooks
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(webhookService.list(user.id));
    }

    /**
     * Registers a new webhook for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the webhook definition (target URL, events, secret, etc.)
     * @return a CREATED response with the persisted webhook
     * @since v2026.2.1
     */
    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(webhookService.create(user.id, body));
    }

    /**
     * Fetches a single webhook owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the webhook to fetch
     * @return an OK response with the webhook, or a 404 if it does not exist
     * @since v2026.2.1
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var wh = webhookService.getById(id, user.id);
        if (wh == null) return notFound("Webhook not found");
        return ok(wh);
    }

    /**
     * Deletes a webhook owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the webhook to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Enables a webhook owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the webhook to enable
     * @return an OK response acknowledging the change
     * @since v2026.2.1
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.setEnabled(id, user.id, true);
        return ok(Map.of("ok", true));
    }

    /**
     * Disables a webhook owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the webhook to disable
     * @return an OK response acknowledging the change
     * @since v2026.2.1
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.setEnabled(id, user.id, false);
        return ok(Map.of("ok", true));
    }

    /**
     * Sends a test payload to a webhook owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the webhook to test
     * @return an OK response confirming the test payload was sent
     * @since v2026.2.1
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.test(id, user.id);
        return ok(Map.of("ok", true, "message", "Test payload sent"));
    }
}
