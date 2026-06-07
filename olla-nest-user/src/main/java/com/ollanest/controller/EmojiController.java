package com.ollanest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Same-origin proxy and on-disk cache for monochrome OpenMoji emoji SVGs.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The front-end renders emoji icons using CSS masks, which require the SVG to be
 * served from the same origin (cross-origin masks are blocked). Rather than
 * bundle the full OpenMoji set, this controller lazily fetches each requested
 * codepoint from the OpenMoji CDN on first use and caches it under
 * {@code data/emoji_cache/}, serving subsequent requests from disk.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Requested codepoints are validated against a strict hex pattern before any
 * filesystem or network access, preventing path traversal and SSRF.</li>
 * <li>Unknown or failed codepoints return a transparent 1×1 SVG with HTTP 200
 * (never a 404) so that CSS masks simply render nothing instead of breaking.</li>
 * <li>Successfully cached responses are served with a long immutable
 * {@code Cache-Control} so browsers cache them aggressively.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/emoji")
public class EmojiController extends BaseController {

    /** Base CDN URL of the OpenMoji black (monochrome) SVG set. */
    private static final String OPENMOJI_BASE = "https://cdn.jsdelivr.net/npm/openmoji@15.0.0/black/svg";

    /** Validates a codepoint path variable: one or more dash-separated hex groups. */
    private static final Pattern CODE_RE = Pattern.compile("^[0-9a-fA-F]{2,6}(?:-[0-9a-fA-F]{2,6})*$");

    /** Transparent 1×1 SVG returned for unknown or invalid codepoints. */
    private static final byte[] BLANK_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"></svg>".getBytes();

    /** Shared HTTP client used to fetch uncached emoji from the CDN. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Filesystem root for application data; the emoji cache lives under {@code emoji_cache}. */
    @Value("${app.data-dir:./data}")
    private String dataDir;

    /**
     * Serves the SVG for a single emoji codepoint, fetching and caching on miss.
     *
     * <p>
     * Validates the codepoint, returns the cached file if present, otherwise
     * fetches it from the OpenMoji CDN and writes it to the cache. Any invalid
     * codepoint, network failure, or non-200 CDN response yields a transparent
     * SVG with HTTP 200 so the caller never sees an error.
     *
     * @param code the emoji codepoint (dash-separated hex groups)
     * @return an HTTP 200 response carrying either the emoji SVG or a transparent
     *         placeholder SVG
     * @since v2026.2.1
     */
    @GetMapping(value = "/{code}.svg", produces = "image/svg+xml")
    public ResponseEntity<byte[]> emojiSvg(@PathVariable String code) {
        code = code.toLowerCase();
        if (!CODE_RE.matcher(code).matches()) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/svg+xml"))
                    .header("Cache-Control", "no-store").body(BLANK_SVG);
        }

        try {
            Path cacheDir = Path.of(dataDir, "emoji_cache");
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve(code + ".svg");

            if (Files.exists(cached)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("image/svg+xml"))
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .body(Files.readAllBytes(cached));
            }

            // Fetch from CDN
            String upper = code.replace("-", "-").toUpperCase().replace("-", "-");
            // OpenMoji uses uppercase with dashes
            String url = OPENMOJI_BASE + "/" + upper + ".svg";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200) {
                Files.write(cached, resp.body());
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("image/svg+xml"))
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .body(resp.body());
            }
        } catch (Exception ignore) {}

        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/svg+xml"))
                .header("Cache-Control", "no-store").body(BLANK_SVG);
    }
}
