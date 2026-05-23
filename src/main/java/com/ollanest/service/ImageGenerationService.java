package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Image generation service supporting:
 *  - DALL-E 3 (OpenAI API)
 *  - Stable Diffusion (self-hosted Automatic1111 API)
 *
 * Provider selection is driven by the {@code imageProvider} setting.
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final DatabaseService dbService;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public ImageGenerationService(DatabaseService dbService, ObjectMapper mapper) {
        this.dbService = dbService;
        this.mapper    = mapper;
    }

    public record ImageResult(String url, String base64, String provider, String model) {}

    /** Generate an image for the given prompt using the configured provider. */
    public ImageResult generate(String prompt, String requestedProvider) throws Exception {
        String provider = (requestedProvider != null && !requestedProvider.isBlank())
                ? requestedProvider
                : dbService.getSetting("imageProvider", "dalle");

        return switch (provider) {
            case "stable-diffusion", "sd" -> generateSD(prompt);
            default -> generateDalle(prompt);
        };
    }

    // ── DALL-E 3 ──────────────────────────────────────────────────────────

    private ImageResult generateDalle(String prompt) throws Exception {
        // Find the OpenAI provider's API key
        String apiKey = resolveOpenAiKey();
        if (apiKey.isBlank()) throw new RuntimeException("OpenAI API key not configured");

        String model = dbService.getSetting("imageModel", "dall-e-3");
        String size  = dbService.getSetting("imageSize",  "1024x1024");

        String body = mapper.writeValueAsString(Map.of(
                "model",           model,
                "prompt",          prompt,
                "n",               1,
                "size",            size,
                "response_format", "url"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/images/generations"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        if (root.has("error"))
            throw new RuntimeException("DALL-E error: " + root.path("error").path("message").asText());

        String url = root.path("data").get(0).path("url").asText();
        return new ImageResult(url, null, "dalle", model);
    }

    // ── Stable Diffusion (Automatic1111 / AUTOMATIC1111 API) ────────────────

    private ImageResult generateSD(String prompt) throws Exception {
        String sdUrl = dbService.getSetting("sdBaseUrl", "http://localhost:7860");
        String body  = mapper.writeValueAsString(Map.of(
                "prompt",           prompt,
                "negative_prompt",  "nsfw, blurry, low quality",
                "steps",            20,
                "width",            512,
                "height",           512,
                "cfg_scale",        7,
                "sampler_index",    "Euler a"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(sdUrl + "/sdapi/v1/txt2img"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        if (!root.has("images") || !root.path("images").isArray() || root.path("images").isEmpty())
            throw new RuntimeException("Stable Diffusion returned no images");

        String base64 = root.path("images").get(0).asText();
        return new ImageResult(null, base64, "stable-diffusion", "sd");
    }

    private String resolveOpenAiKey() {
        // Look for OpenAI provider in api_providers table
        return dbService.getSetting("openaiApiKey", "");
    }
}
