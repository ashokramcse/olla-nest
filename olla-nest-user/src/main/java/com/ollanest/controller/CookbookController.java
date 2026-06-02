package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.CookbookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Model Cookbook API — hardware detection, model catalog, download management.
 * Admin-only for downloads/serves; read-only catalog is available to all users.
 */
@RestController
@RequestMapping("/api/cookbook")
public class CookbookController extends BaseController {

    private final CookbookService cookbookService;

    public CookbookController(CookbookService cookbookService) {
        this.cookbookService = cookbookService;
    }

    @GetMapping("/hardware")
    public ResponseEntity<?> hardware(HttpServletRequest req) {
        requireAuth(req);
        return ok(cookbookService.detectHardware());
    }

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(HttpServletRequest req) {
        requireAuth(req);
        return ok(cookbookService.getCatalog());
    }

    @GetMapping("/downloads")
    public ResponseEntity<?> downloads(HttpServletRequest req) {
        User user = requireAuth(req);
        if (!"admin".equals(user.role)) return forbidden("Admin only");
        return ok(cookbookService.getDownloads());
    }

    @PostMapping("/download")
    public SseEmitter download(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        if (!"admin".equals(user.role)) throw new ForbiddenException("Admin only");

        String hfRepo = (String) body.get("hf_repo");
        String hfFile = (String) body.get("hf_filename");
        if (hfRepo == null || hfRepo.isBlank()) throw new IllegalArgumentException("hf_repo is required");

        SseEmitter emitter = new SseEmitter(7_200_000L); // 2h timeout
        emitter.onTimeout(emitter::complete);
        cookbookService.startDownload(hfRepo, hfFile, emitter);
        return emitter;
    }

    private ResponseEntity<?> forbidden(String msg) {
        return ResponseEntity.status(403).body(Map.of("ok", false, "error", msg));
    }
}
