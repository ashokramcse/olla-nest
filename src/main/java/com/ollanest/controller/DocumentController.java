package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController extends BaseController {

    private final RagService ragService;
    private final JdbcTemplate db;

    public DocumentController(RagService ragService, JdbcTemplate db) {
        this.ragService = ragService;
        this.db = db;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuth(req);
        if (err != null) return err;
        List<Map<String, Object>> docs = ragService.listDocuments();
        return ResponseEntity.ok(Map.of("ok", true, "documents", docs));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "scope", defaultValue = "global") String scope,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;
        User user = getUser(req);

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        if (file.getSize() > 10 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("error", "File too large (max 10 MB)"));

        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String type = file.getContentType() != null ? file.getContentType() : "text/plain";
        String docId = "doc-" + Long.toString(System.currentTimeMillis(), 36) + "-" +
                       Long.toString((long)(Math.random() * 36L * 36L * 36L * 36L), 36);

        try {
            Map<String, Object> result = ragService.ingestDocument(docId, name, type, file.getSize(),
                file.getInputStream(), user.name, scope);
            return ResponseEntity.ok(Map.of("ok", true, "document", result));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;
        List<Map<String, Object>> docs = db.queryForList("SELECT id FROM rag_documents WHERE id = ?", id);
        if (docs.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "Document not found"));
        ragService.deleteDocument(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
