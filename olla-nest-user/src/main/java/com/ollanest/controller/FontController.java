package com.ollanest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * REST controller that exposes user-supplied ("custom") fonts dropped into the
 * static asset tree so the front-end can offer them in font pickers.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users can place arbitrary font files under {@code static/fonts/custom/} to
 * extend the editor's font choices without a rebuild. This controller scans
 * that directory at request time and reports the available families, deriving a
 * human-friendly family name from each filename so the UI does not need to embed
 * any font metadata of its own.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Only files with a recognised web-font extension ({@code .ttf}, {@code .otf},
 * {@code .woff}, {@code .woff2}) are considered.</li>
 * <li>Family names are derived heuristically from filenames: weight/style
 * suffixes are stripped and camelCase is split into words, e.g.
 * {@code "JetBrainsMono-Regular.woff2"} becomes {@code "JetBrains Mono"}.</li>
 * <li>The custom directory is created on demand so a fresh install returns an
 * empty list rather than an error.</li>
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
@RequestMapping("/api/fonts")
public class FontController extends BaseController {

    /** Web-font file extensions recognised when scanning the custom fonts directory. */
    private static final Set<String> FONT_EXTS = Set.of(".ttf", ".otf", ".woff", ".woff2");

    /** Matches a trailing weight/style token so it can be stripped from a derived family name. */
    private static final Pattern WEIGHT_SUFFIX = Pattern.compile(
            "[-_ ]?(Thin|ExtraLight|UltraLight|Light|Regular|Medium|SemiBold|DemiBold|Bold|ExtraBold|UltraBold|Black|Heavy|Italic|Oblique|Variable|VF)$",
            Pattern.CASE_INSENSITIVE);

    /** Filesystem root of the served static assets; the custom fonts live under {@code fonts/custom}. */
    @Value("${app.static-dir:./public}")
    private String staticDir;

    /**
     * Lists every custom font grouped by derived family name.
     *
     * <p>
     * Creates the custom fonts directory if it does not yet exist, then scans it
     * for recognised web-font files. Each file is grouped under its derived family
     * name and reported with its public URL and format.
     *
     * @return an OK response whose {@code fonts} entry maps each family name to the
     *         list of its font files (file name, public URL, format)
     * @throws IOException if the custom fonts directory cannot be created or listed
     * @since v2026.2.1
     */
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

    /**
     * Derives a human-friendly font-family name from a font file name.
     *
     * <p>
     * Strips the extension and any trailing weight/style suffix, splits camelCase
     * boundaries into spaces, and collapses separators. Falls back to the original
     * filename if the derivation yields an empty string.
     *
     * @param filename the font file name (e.g. {@code "JetBrainsMono-Regular.woff2"})
     * @return the derived family name (e.g. {@code "JetBrains Mono"})
     * @since v2026.2.1
     */
    private String deriveFamily(String filename) {
        String name = filename.substring(0, filename.lastIndexOf('.'));
        name = WEIGHT_SUFFIX.matcher(name).replaceAll("");
        name = name.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        name = name.replaceAll("[-_]+", " ").trim();
        return name.isEmpty() ? filename : name;
    }

    /**
     * Returns the lower-cased file extension (including the leading dot) of a name.
     *
     * @param filename the file name to inspect
     * @return the extension including the dot (e.g. {@code ".woff2"}), or an empty
     *         string if the name has no extension
     * @since v2026.2.1
     */
    private String getExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
