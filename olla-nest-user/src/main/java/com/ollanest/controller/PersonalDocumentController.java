package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.PersonalDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Personal document upload endpoint — per-user file storage with automatic RAG ingestion.
 * Supports PDF, DOCX, PPTX, XLSX, TXT, MD, CSV, and code files up to 25 MB.
 */
@RestController
@RequestMapping("/api/documents/personal")
public class PersonalDocumentController extends BaseController {

    private final PersonalDocumentService personalDocService;

    public PersonalDocumentController(PersonalDocumentService personalDocService) {
        this.personalDocService = personalDocService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpServletRequest req,
            @RequestParam("file") MultipartFile file) {
        User user = requireAuth(req);
        try {
            var result = personalDocService.upload(user.id, file.getBytes(), file.getOriginalFilename());
            return created(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return serverError("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/extract-text")
    public ResponseEntity<?> extractText(HttpServletRequest req,
            @RequestParam("file") MultipartFile file) {
        requireAuth(req);
        try {
            String text = personalDocService.extractText(file.getBytes(), file.getOriginalFilename());
            return ok(Map.of("text", text != null ? text : "", "chars", text != null ? text.length() : 0));
        } catch (Exception e) {
            return serverError("Text extraction failed: " + e.getMessage());
        }
    }
}
