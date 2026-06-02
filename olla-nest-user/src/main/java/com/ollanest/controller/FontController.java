package com.ollanest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Custom font discovery — serves user-supplied font files from static/fonts/custom/.
 * Derives font-family names from filenames: "JetBrainsMono-Regular.woff2" → "JetBrains Mono"
 */
@RestController
@RequestMapping("/api/fonts")
public class FontController extends BaseController {

    private static final Set<String> FONT_EXTS = Set.of(".ttf", ".otf", ".woff", ".woff2");
    private static final Pattern WEIGHT_SUFFIX = Pattern.compile(
            "[-_ ]?(Thin|ExtraLight|UltraLight|Light|Regular|Medium|SemiBold|DemiBold|Bold|ExtraBold|UltraBold|Black|Heavy|Italic|Oblique|Variable|VF)$",
            Pattern.CASE_INSENSITIVE);

    @Value("${app.static-dir:./public}")
    private String staticDir;

    @GetMapping("/custom")
    public ResponseEntity<?> listCustomFonts() throws IOException {
        Path customFontsDir = Path.of(staticDir, "fonts", "custom");
        if (!Files.exists(customFontsDir)) {
            Files.createDirectories(customFontsDir);
        }

        Map<String, List<Map<String, String>>> families = new TreeMap<>();
        if (Files.isDirectory(customFontsDir)) {
            try (var stream = Files.list(customFontsDir)) {
                stream.filter(p -> FONT_EXTS.contains(getExt(p.getFileName().toString())))
                      .sorted()
                      .forEach(p -> {
                          String file = p.getFileName().toString();
                          String ext = getExt(file);
                          String family = deriveFamily(file);
                          families.computeIfAbsent(family, k -> new ArrayList<>())
                                  .add(Map.of("file", file, "url", "/fonts/custom/" + file, "format", ext.substring(1)));
                      });
            }
        }
        return ok(Map.of("fonts", families));
    }

    private String deriveFamily(String filename) {
        String name = filename.substring(0, filename.lastIndexOf('.'));
        name = WEIGHT_SUFFIX.matcher(name).replaceAll("");
        name = name.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        name = name.replaceAll("[-_]+", " ").trim();
        return name.isEmpty() ? filename : name;
    }

    private String getExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
