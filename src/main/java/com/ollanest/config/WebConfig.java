package com.ollanest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC static resource handler configuration.
 *
 * <p>Maps the {@code public/} directory (configurable via the {@code app.static-dir}
 * property or the {@code STATIC_DIR} environment variable) as the root resource
 * location so that frontend assets — {@code app.html}, {@code admin.html},
 * {@code styles.css}, vendor JS bundles, favicons, and SVG icons — are served
 * directly by Spring MVC without hitting any controller.
 *
 * <p>API routes defined in controllers take precedence over these resource mappings
 * because Spring MVC resolves controller {@code @RequestMapping} handlers first.
 * The resource handler patterns below act as a fallback for non-API paths.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>The static directory is externalised as a property so deployments can
 *       point to a CDN-mounted volume or a built frontend dist folder without
 *       recompilation.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Path to the directory containing static frontend assets. */
    @Value("${app.static-dir:./public}")
    private String staticDir;

    /**
     * Registers URL patterns that should be served as static files from the
     * configured {@code staticDir}.
     *
     * <p>Patterns registered:
     * <ul>
     *   <li>{@code /assets/**} — bundled JS/CSS/image assets</li>
     *   <li>{@code /*.js}, {@code /*.css} — root-level scripts and stylesheets</li>
     *   <li>{@code /*.ico}, {@code /*.png}, {@code /*.svg} — icon and image files</li>
     * </ul>
     *
     * @param  registry  the Spring MVC resource handler registry
     * @since   v2026.1.0  — initial Java Spring Boot migration
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static files from ./public directory.
        // Note: API routes take precedence since they are defined in controllers first.
        // The patterns below are a fallback for assets (CSS, JS, images).
        registry.addResourceHandler("/assets/**", "/*.js", "/*.css", "/*.ico", "/*.png", "/*.svg")
                .addResourceLocations("file:" + staticDir + "/");
    }
}
