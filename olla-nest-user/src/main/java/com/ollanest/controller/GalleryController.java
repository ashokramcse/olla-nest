package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.GalleryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Gallery API — albums, image upload with EXIF + dedup, editor drafts. */
@RestController
@RequestMapping("/api/gallery")
public class GalleryController extends BaseController {

    private final GalleryService galleryService;

    public GalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    // ── Albums ────────────────────────────────────────────────────────────────

    @GetMapping("/albums")
    public ResponseEntity<?> listAlbums(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(galleryService.listAlbums(user.id));
    }

    @PostMapping("/albums")
    public ResponseEntity<?> createAlbum(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(galleryService.createAlbum(user.id, body));
    }

    @DeleteMapping("/albums/{id}")
    public ResponseEntity<?> deleteAlbum(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        galleryService.deleteAlbum(id, user.id);
        return ok(Map.of("ok", true));
    }

    // ── Images ────────────────────────────────────────────────────────────────

    @GetMapping("/images")
    public ResponseEntity<?> listImages(HttpServletRequest req,
            @RequestParam(required = false) String albumId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        User user = requireAuth(req);
        return ok(galleryService.listImages(user.id, albumId, page, pageSize));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpServletRequest req,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String albumId) {
        User user = requireAuth(req);
        try {
            var result = galleryService.uploadImage(user.id, file.getBytes(), file.getOriginalFilename(), albumId);
            if (Boolean.TRUE.equals(result.get("duplicate"))) {
                return ok(Map.of("ok", false, "duplicate", true, "id", result.get("id")));
            }
            return created(result);
        } catch (Exception e) {
            return serverError("Upload failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/images/{id}")
    public ResponseEntity<?> deleteImage(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        galleryService.deleteImage(id, user.id);
        return ok(Map.of("ok", true));
    }

    // ── Editor Drafts ─────────────────────────────────────────────────────────

    @GetMapping("/drafts")
    public ResponseEntity<?> listDrafts(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(galleryService.listDrafts(user.id));
    }

    @PostMapping("/drafts")
    public ResponseEntity<?> saveDraft(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(galleryService.saveDraft(user.id, body));
    }

    @GetMapping("/drafts/{id}")
    public ResponseEntity<?> getDraft(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        var draft = galleryService.getDraft(id, user.id);
        if (draft == null) return notFound("Draft not found");
        return ok(draft);
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<?> deleteDraft(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        galleryService.deleteDraft(id, user.id);
        return ok(Map.of("ok", true));
    }
}
