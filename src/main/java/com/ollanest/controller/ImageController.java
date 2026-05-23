package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ImageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Image generation endpoint.
 *
 * <p>POST /api/images/generate — generates an image from a text prompt.
 */
@RestController
@RequestMapping("/api/images")
public class ImageController extends BaseController {

    private final ImageGenerationService imageService;
    private final JdbcTemplate db;

    public ImageController(ImageGenerationService imageService, JdbcTemplate db) {
        this.imageService = imageService;
        this.db = db;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuth(req);
        if (err != null) return err;
        User user = getUser(req);

        String prompt   = (String) body.getOrDefault("prompt", "");
        String provider = (String) body.getOrDefault("provider", null);

        if (prompt.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));

        String logId = "ig-" + Long.toString(System.currentTimeMillis(), 36);
        try {
            ImageGenerationService.ImageResult result = imageService.generate(prompt, provider);

            // Log the generation
            db.update("INSERT INTO image_generation_log (id, user_id, prompt, provider, model, status, created_at) VALUES (?,?,?,?,?,?,?)",
                    logId, user.id, prompt, result.provider(), result.model(), "ok", Instant.now().toString());

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("ok", true);
            response.put("provider", result.provider());
            if (result.url() != null) response.put("imageUrl", result.url());
            if (result.base64() != null) response.put("base64", result.base64());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            db.update("INSERT INTO image_generation_log (id, user_id, prompt, provider, model, status, error, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    logId, user.id, prompt, provider != null ? provider : "dalle", null, "error", e.getMessage(), Instant.now().toString());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
