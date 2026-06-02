package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.EmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Email API — account management, inbox browsing, compose/send, AI triage.
 */
@RestController
@RequestMapping("/api/email")
public class EmailController extends BaseController {

    private final EmailService emailService;

    public EmailController(EmailService emailService) {
        this.emailService = emailService;
    }

    // ── Accounts ──────────────────────────────────────────────────────────────

    @GetMapping("/accounts")
    public ResponseEntity<?> listAccounts(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(emailService.listAccounts(user.id));
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(HttpServletRequest req,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(emailService.createAccount(user.id, body));
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<?> getAccount(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var account = emailService.getAccount(id, user.id);
        if (account == null) return notFound("Account not found");
        return ok(account);
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAccount(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        emailService.deleteAccount(id, user.id);
        return ok(Map.of("ok", true));
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    @GetMapping("/accounts/{accountId}/messages")
    public ResponseEntity<?> listMessages(HttpServletRequest req,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        User user = requireAuth(req);
        return ok(emailService.listMessages(accountId, user.id, folder, page, pageSize));
    }

    @GetMapping("/accounts/{accountId}/messages/{messageId}")
    public ResponseEntity<?> getMessage(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String messageId) {
        requireAuth(req);
        var msg = emailService.getMessage(messageId, accountId);
        if (msg == null) return notFound("Message not found");
        return ok(msg);
    }

    @GetMapping("/accounts/{accountId}/threads/{threadId}")
    public ResponseEntity<?> getThread(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String threadId) {
        requireAuth(req);
        return ok(emailService.getThread(threadId, accountId));
    }

    @PostMapping("/accounts/{accountId}/messages/{messageId}/read")
    public ResponseEntity<?> markRead(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String messageId) {
        requireAuth(req);
        emailService.markRead(messageId, accountId);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/accounts/{accountId}/messages/{messageId}/star")
    public ResponseEntity<?> markStarred(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String messageId,
            @RequestBody Map<String, Object> body) {
        requireAuth(req);
        boolean starred = Boolean.TRUE.equals(body.get("starred"));
        emailService.markStarred(messageId, accountId, starred);
        return ok(Map.of("ok", true));
    }

    @DeleteMapping("/accounts/{accountId}/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String messageId) {
        requireAuth(req);
        emailService.deleteMessage(messageId, accountId);
        return ok(Map.of("ok", true));
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    @PostMapping("/accounts/{accountId}/send")
    public ResponseEntity<?> send(HttpServletRequest req,
            @PathVariable String accountId,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        try {
            emailService.sendEmail(accountId, user.id, body);
            return ok(Map.of("ok", true));
        } catch (Exception e) {
            return serverError("Failed to send email: " + e.getMessage());
        }
    }

    // ── AI ────────────────────────────────────────────────────────────────────

    @PostMapping("/accounts/{accountId}/messages/{messageId}/reply-draft")
    public ResponseEntity<?> replyDraft(HttpServletRequest req,
            @PathVariable String accountId, @PathVariable String messageId) {
        User user = requireAuth(req);
        String draft = emailService.generateReplyDraft(messageId, accountId, user.id);
        return ok(Map.of("draft", draft));
    }
}
