package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Outgoing webhook management API. */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController extends BaseController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(webhookService.list(user.id));
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(webhookService.create(user.id, body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var wh = webhookService.getById(id, user.id);
        if (wh == null) return notFound("Webhook not found");
        return ok(wh);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.setEnabled(id, user.id, true);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.setEnabled(id, user.id, false);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        webhookService.test(id, user.id);
        return ok(Map.of("ok", true, "message", "Test payload sent"));
    }
}
