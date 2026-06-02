package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ContactsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Contacts API — CRUD with search and vCard export. */
@RestController
@RequestMapping("/api/contacts")
public class ContactsController extends BaseController {

    private final ContactsService contactsService;

    public ContactsController(ContactsService contactsService) {
        this.contactsService = contactsService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(defaultValue = "100") int limit) {
        User user = requireAuth(req);
        return ok(contactsService.list(user.id, limit));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(HttpServletRequest req, @RequestParam String q) {
        User user = requireAuth(req);
        return ok(contactsService.search(user.id, q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var contact = contactsService.getById(id, user.id);
        if (contact == null) return notFound("Contact not found");
        return ok(contact);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(contactsService.create(user.id, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(contactsService.update(id, user.id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        contactsService.delete(id, user.id);
        return ok(Map.of("ok", true));
    }

    @GetMapping("/export.vcf")
    public ResponseEntity<String> exportVCard(HttpServletRequest req) {
        User user = requireAuth(req);
        String vcf = contactsService.exportVCard(user.id);
        return ResponseEntity.ok()
                .header("Content-Type", "text/vcard; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"contacts.vcf\"")
                .body(vcf);
    }
}
