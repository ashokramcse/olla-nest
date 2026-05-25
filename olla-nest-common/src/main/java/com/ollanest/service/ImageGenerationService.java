package com.ollanest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI image generation service supporting DALL-E 3 and self-hosted Stable
 * Diffusion.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest users can generate images directly from the chat composer by
 * clicking the image generation button. {@code ImageGenerationService} is the
 * single integration point for image-generation APIs — it reads provider
 * configuration from the settings table and dispatches to the appropriate
 * backend, returning a uniform {@link ImageResult} record regardless of which
 * provider produced the image. Controllers and chat flows never touch
 * provider-specific HTTP or JSON directly.
 *
 * <h3>Supported providers</h3>
 * <ul>
 * <li><b>DALL-E 3</b> (default) — OpenAI's image generation API at
 * {@code https://api.openai.com/v1/images/generations}. Requires the
 * {@code openaiApiKey} setting and returns a time-limited CDN URL.</li>
 * <li><b>Stable Diffusion</b> — Automatic1111 WebUI API at a self-hosted base
 * URL configured via {@code sdBaseUrl} (default:
 * {@code http://localhost:7860}). Returns a base64-encoded PNG string.</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Provider selection is driven by the {@code imageProvider} setting, or
 * overridden per-request by the caller (e.g., {@code ImageController}).</li>
 * <li>DALL-E calls have a 60-second timeout; Stable Diffusion has 120 seconds
 * because local GPU inference can be slower.</li>
 * <li>The service does not store generated images — it passes the URL or base64
 * back to the caller for inline display in the chat UI.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; DALL-E 3 and Stable Diffusion
 * (Automatic1111) backends; {@link ImageResult} record; configurable model,
 * size, and SD parameters.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see com.ollanest.controller.ImageController
 */
@Service
public class ImageGenerationService {

	/** Settings and DB access used to read API keys and provider configuration. */
	private final DatabaseService dbService;

	/**
	 * Shared Jackson mapper for serialising request bodies and parsing responses.
	 */
	private final ObjectMapper mapper;

	/**
	 * HTTP client shared across all image-generation calls; 15-second connection
	 * timeout. Note: response timeouts are set per-request because DALL-E and SD
	 * have very different expected latencies.
	 */
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	/**
	 * Constructor-injects the database service and JSON mapper.
	 *
	 * @param dbService the database service for reading provider settings and API
	 *                  keys
	 * @param mapper    the shared Jackson {@link ObjectMapper}
	 * @since v2026.1.4
	 */
	public ImageGenerationService(DatabaseService dbService, ObjectMapper mapper) {
		this.dbService = dbService;
		this.mapper = mapper;
	}

	// -------------------------------------------------------------------------
	// Public record type
	// -------------------------------------------------------------------------

	/**
	 * Immutable result of an image generation call.
	 *
	 * @param url      a time-limited CDN URL to the generated image (DALL-E), or
	 *                 {@code null} if the result is base64-encoded
	 * @param base64   base64-encoded PNG data (Stable Diffusion), or {@code null}
	 *                 if a URL was returned instead
	 * @param provider the provider that generated the image ({@code "dalle"} or
	 *                 {@code "stable-diffusion"})
	 * @param model    the specific model used (e.g. {@code "dall-e-3"} or
	 *                 {@code "sd"})
	 * @since v2026.1.4
	 */
	public record ImageResult(String url, String base64, String provider, String model) {
	}

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Generates an image for the given prompt using the configured (or requested)
	 * provider.
	 *
	 * <p>
	 * If {@code requestedProvider} is non-null and non-blank it overrides the
	 * system setting. The strings {@code "stable-diffusion"} and {@code "sd"} both
	 * select the Stable Diffusion backend; anything else (including
	 * {@code "dalle"}) selects DALL-E 3.
	 *
	 * @param prompt            the natural-language description of the image to
	 *                          generate; must not be null or blank
	 * @param requestedProvider optional provider override ({@code "dalle"},
	 *                          {@code "sd"}, {@code "stable-diffusion"}); use
	 *                          {@code null} to read from settings
	 * @return an {@link ImageResult} with either {@code url} or {@code base64}
	 *         populated depending on the provider
	 * @throws Exception if the provider API returns an error, the API key is
	 *                   missing, or a network/parse error occurs
	 * @since v2026.1.4
	 */
	public ImageResult generate(String prompt, String requestedProvider) throws Exception {
		String provider = (requestedProvider != null && !requestedProvider.isBlank()) ? requestedProvider
				: dbService.getSetting("imageProvider", "dalle");
		return switch (provider) {
		case "stable-diffusion", "sd" -> generateSD(prompt);
		default -> generateDalle(prompt);
		};
	}

	// -------------------------------------------------------------------------
	// Provider: DALL-E 3
	// -------------------------------------------------------------------------

	/**
	 * Generates an image using the OpenAI DALL-E 3 API.
	 *
	 * <p>
	 * Reads {@code imageModel} (default: {@code "dall-e-3"}) and {@code imageSize}
	 * (default: {@code "1024x1024"}) from settings. The API returns a time-limited
	 * CDN URL that the frontend can {@code <img src="...">} directly.
	 *
	 * @param prompt the image description
	 * @return an {@link ImageResult} with a non-null {@code url} field
	 * @throws RuntimeException if {@code openaiApiKey} is not configured, or the
	 *                          API returns an error object in the response JSON
	 * @throws Exception        on HTTP or JSON parse failure
	 * @since v2026.1.4
	 */
	private ImageResult generateDalle(String prompt) throws Exception {
		String apiKey = resolveOpenAiKey();
		if (apiKey.isBlank())
			throw new RuntimeException("OpenAI API key not configured");
		String model = dbService.getSetting("imageModel", "dall-e-3");
		String size = dbService.getSetting("imageSize", "1024x1024");
		String body = mapper.writeValueAsString(
				Map.of("model", model, "prompt", prompt, "n", 1, "size", size, "response_format", "url"));
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://api.openai.com/v1/images/generations"))
				.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode root = mapper.readTree(resp.body());
		if (root.has("error")) {
			throw new RuntimeException("DALL-E error: " + root.path("error").path("message").asText());
		}
		String url = root.path("data").get(0).path("url").asText();
		return new ImageResult(url, null, "dalle", model);
	}

	// -------------------------------------------------------------------------
	// Provider: Stable Diffusion (Automatic1111 API)
	// -------------------------------------------------------------------------

	/**
	 * Generates an image via a self-hosted Stable Diffusion Automatic1111 WebUI
	 * API.
	 *
	 * <p>
	 * POSTs to {@code {sdBaseUrl}/sdapi/v1/txt2img} with a fixed set of generation
	 * parameters (20 steps, 512×512, Euler a sampler, negative prompt filtering
	 * NSFW/blurry content). The response contains base64-encoded PNG images in the
	 * {@code images} array.
	 *
	 * @param prompt the image description
	 * @return an {@link ImageResult} with a non-null {@code base64} field
	 * @throws RuntimeException if the API response contains no images
	 * @throws Exception        on HTTP or JSON parse failure
	 * @since v2026.1.4
	 */
	private ImageResult generateSD(String prompt) throws Exception {
		String sdUrl = dbService.getSetting("sdBaseUrl", "http://localhost:7860");
		String body = mapper.writeValueAsString(Map.of("prompt", prompt, "negative_prompt", "nsfw, blurry, low quality",
				"steps", 20, "width", 512, "height", 512, "cfg_scale", 7, "sampler_index", "Euler a"));
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(sdUrl + "/sdapi/v1/txt2img"))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode root = mapper.readTree(resp.body());
		if (!root.has("images") || !root.path("images").isArray() || root.path("images").isEmpty()) {
			throw new RuntimeException("Stable Diffusion returned no images");
		}
		String base64 = root.path("images").get(0).asText();
		return new ImageResult(null, base64, "stable-diffusion", "sd");
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/**
	 * Resolves the OpenAI API key from the application settings table.
	 *
	 * <p>
	 * Currently reads the {@code openaiApiKey} setting directly. Future versions
	 * may look up the key from the {@code api_providers} table by provider type.
	 *
	 * @return the OpenAI API key string, or {@code ""} if not configured
	 * @since v2026.1.4
	 */
	private String resolveOpenAiKey() {
		return dbService.getSetting("openaiApiKey", "");
	}
}
