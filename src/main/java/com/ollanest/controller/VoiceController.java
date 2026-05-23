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
 * REST API for voice input (speech-to-text) and voice output (text-to-speech).
 *
 * <h3>Why this class exists</h3>
 * <p>Wraps OpenAI Whisper (STT) and OpenAI TTS behind two thin HTTP endpoints so
 * the browser-based chat UI can send audio recordings and receive synthesised speech
 * without embedding API keys in the frontend. Both endpoints enforce session auth
 * and CSRF protection.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Transcription enforces a 25 MB ceiling — the hard limit imposed by Whisper.
 *       Files above this threshold are rejected with 400 before any data is read into
 *       memory.</li>
 *   <li>TTS input is silently truncated to 4 096 characters (the OpenAI TTS API
 *       limit) rather than returning an error, because the most common caller is the
 *       assistant message renderer which may receive very long responses.</li>
 *   <li>The {@code /speak} response carries binary MP3 bytes with
 *       {@code Content-Type: audio/mpeg} so the browser can play it directly via the
 *       HTML {@code <audio>} element.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial Java Spring Boot migration</li>
 *   <li>v2026.1.4 — added voice/speak TTS endpoint; removed Firefox Web Speech API
 *                   fallback (Chrome/Edge only)</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController extends BaseController {

    /** Delegates Whisper STT and OpenAI TTS calls to the voice service. */
    private final VoiceService voiceService;

    /**
     * Constructor-injects the voice service.
     *
     * @param  voiceService  the Whisper / TTS service
     * @since  v2026.1.0
     */
    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /**
     * Transcribes an uploaded audio file to plain text using OpenAI Whisper.
     *
     * <p>Endpoint: {@code POST /api/voice/transcribe}
     * <p>Content-Type: {@code multipart/form-data} — field name {@code audio}.
     *
     * <p>Accepted formats: {@code wav}, {@code mp3}, {@code webm}, {@code ogg},
     * {@code m4a}. Files larger than 25 MB are rejected before transcription
     * to respect the Whisper API file-size limit.
     *
     * @param  file  the multipart audio file; must not be empty and must be ≤ 25 MB
     * @param  req   the current HTTP request (session cookie used for auth + CSRF check)
     * @return       200 OK with {@code {ok: true, text: "..."}} on success;
     *               400 if the file is absent or too large;
     *               401 if the caller is not authenticated or CSRF header is missing;
     *               500 with {@code {ok: false, error}} on Whisper API failure
     * @since  v2026.1.0
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("audio") MultipartFile file,
            HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAuthWithCsrf(req);
        if (err != null) return err;

        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "No audio file provided"));

        // Max 25 MB (Whisper limit)
        if (file.getSize() > 25 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Audio file too large (max 25 MB)"));

        try {
            String text = voiceService.transcribe(file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("ok", true, "text", text));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * Converts text to speech and returns the result as MP3 audio bytes.
     *
     * <p>Endpoint: {@code POST /api/voice/speak}
     * <p>Content-Type: {@code application/json}
     *
     * <p>Input text longer than 4 096 characters is silently truncated to match the
     * OpenAI TTS API character limit. The response body is raw MP3 binary data, not
     * a JSON envelope, so callers should check {@code Content-Type: audio/mpeg}.
     *
     * @param  body  JSON request body with:
     *               <ul>
     *                 <li>{@code text} (String, required) — text to synthesise; max 4 096 chars</li>
     *                 <li>{@code voice} (String, optional, default {@code "alloy"}) —
     *                     OpenAI voice ID (e.g. {@code "alloy"}, {@code "echo"}, {@code "nova"})</li>
     *               </ul>
     * @param  req   the current HTTP request (session cookie used for auth + CSRF check)
     * @return       200 OK with {@code Content-Type: audio/mpeg} and MP3 byte body on success;
     *               400 if {@code text} is blank;
     *               401 if the caller is not authenticated or CSRF header is missing;
     *               500 with {@code {ok: false, error}} on TTS API failure
     * @since  v2026.1.4
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
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "text is required"));
        if (text.length() > 4096)
            text = text.substring(0, 4096); // TTS limit

        try {
            byte[] mp3 = voiceService.speak(text, voice);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.mp3\"")
                    .body(mp3);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
