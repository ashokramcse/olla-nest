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
 * Same-origin emoji SVG proxy — caches OpenMoji SVGs locally on first request.
 *
 * The frontend uses CSS masks for monochrome emoji icons.
 * Codepoints are fetched from the OpenMoji CDN once and cached in data/emoji_cache/.
 * Unknown codepoints return a transparent SVG (no 404) so CSS masks show nothing.
 */
@RestController
@RequestMapping("/api/emoji")
public class EmojiController extends BaseController {

    private static final String OPENMOJI_BASE = "https://cdn.jsdelivr.net/npm/openmoji@15.0.0/black/svg";
    private static final Pattern CODE_RE = Pattern.compile("^[0-9a-fA-F]{2,6}(?:-[0-9a-fA-F]{2,6})*$");
    private static final byte[] BLANK_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"></svg>".getBytes();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${app.data-dir:./data}")
    private String dataDir;

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
