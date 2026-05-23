package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Voice service:
 *  - STT: OpenAI Whisper API  (POST /v1/audio/transcriptions)
 *  - TTS: OpenAI TTS API      (POST /v1/audio/speech)
 *
 * Requires an OpenAI API key stored in the {@code openaiApiKey} setting.
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final DatabaseService dbService;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public VoiceService(DatabaseService dbService, ObjectMapper mapper) {
        this.dbService = dbService;
        this.mapper    = mapper;
    }

    // ── Speech-to-Text (Whisper) ───────────────────────────────────────────

    /**
     * Transcribe audio bytes to text using OpenAI Whisper API.
     * @param audioData raw audio bytes (wav, mp3, webm, ogg, m4a)
     * @param filename  file name including extension (used for MIME detection)
     * @return transcribed text
     */
    public String transcribe(byte[] audioData, String filename) throws Exception {
        String apiKey = dbService.getSetting("openaiApiKey", "");
        if (apiKey.isBlank()) throw new RuntimeException("OpenAI API key not configured for transcription");

        String boundary = "----OllaVoice" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String nl = "\r\n";

        // Part: model
        body.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"model\"" + nl + nl + "whisper-1" + nl).getBytes(StandardCharsets.UTF_8));

        // Part: file
        String safeName = filename != null ? filename : "audio.wav";
        body.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"" + nl).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: audio/wav" + nl + nl).getBytes(StandardCharsets.UTF_8));
        body.write(audioData);
        body.write(nl.getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--" + nl).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        if (root.has("error"))
            throw new RuntimeException("Whisper error: " + root.path("error").path("message").asText());

        return root.path("text").asText();
    }

    // ── Text-to-Speech ─────────────────────────────────────────────────────

    /**
     * Convert text to speech using OpenAI TTS API.
     * @param text  text to speak
     * @param voice one of: alloy, echo, fable, onyx, nova, shimmer
     * @return MP3 audio bytes
     */
    public byte[] speak(String text, String voice) throws Exception {
        String apiKey = dbService.getSetting("openaiApiKey", "");
        if (apiKey.isBlank()) throw new RuntimeException("OpenAI API key not configured for TTS");

        String safeVoice = (voice != null && !voice.isBlank()) ? voice : "alloy";
        String body = mapper.writeValueAsString(java.util.Map.of(
                "model", "tts-1",
                "input", text,
                "voice", safeVoice));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200)
            throw new RuntimeException("TTS API error: HTTP " + resp.statusCode());
        return resp.body();
    }
}
