package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.VoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Voice endpoints:
 *  POST /api/voice/transcribe — audio file → text (Whisper STT)
 *  POST /api/voice/speak      — text → MP3 bytes  (OpenAI TTS)
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController extends BaseController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /**
     * Transcribe an audio file to text.
     * Accepts multipart/form-data with field "audio" (wav, mp3, webm, ogg, m4a).
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("audio") MultipartFile file,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No audio file provided"));

        // Max 25 MB (Whisper limit)
        if (file.getSize() > 25 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("error", "Audio file too large (max 25 MB)"));

        try {
            String text = voiceService.transcribe(file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("ok", true, "text", text));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Convert text to speech (MP3).
     * Body: { "text": "...", "voice": "alloy" }
     */
    @PostMapping("/speak")
    public ResponseEntity<?> speak(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;

        String text  = (String) body.getOrDefault("text", "");
        String voice = (String) body.getOrDefault("voice", "alloy");

        if (text.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        if (text.length() > 4096)
            text = text.substring(0, 4096); // TTS limit

        try {
            byte[] mp3 = voiceService.speak(text, voice);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.mp3\"")
                    .body(mp3);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
