package com.ollanest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC static resource handler configuration.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Maps the {@code public/} directory (configurable via the
 * {@code app.static-dir} property or the {@code STATIC_DIR} environment
 * variable) as the root resource location so that frontend assets —
 * {@code app.html}, {@code admin.html}, {@code styles.css}, vendor JS bundles,
 * favicons, and SVG icons — are served directly by Spring MVC without hitting
 * any controller.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Spring MVC resolves controller {@code @RequestMapping} handlers before
 * resource handlers, so API routes always take precedence over these
 * static-file patterns.</li>
 * <li>The static directory is externalised as a property so deployments can
 * point to a CDN-mounted volume or a built frontend dist folder without
 * recompilation.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration; replaces Node.js {@code express.static}
 * middleware</li>
 * <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	/**
	 * Absolute or relative path to the directory containing static frontend assets.
	 * Resolved from the {@code app.static-dir} property, defaulting to
	 * {@code ./public}.
	 */
	@Value("${app.static-dir:./public}")
	private String staticDir;

	/**
	 * Registers URL patterns that should be served as static files from the
	 * configured {@link #staticDir}.
	 *
	 * <p>
	 * Patterns registered:
	 * <ul>
	 * <li>{@code /assets/**} — bundled JS/CSS/image assets</li>
	 * <li>{@code /*.js} — root-level JavaScript files</li>
	 * <li>{@code /*.css} — root-level stylesheets</li>
	 * <li>{@code /*.ico} — favicon files</li>
	 * <li>{@code /*.png} — root-level PNG images</li>
	 * <li>{@code /*.svg} — root-level SVG icons</li>
	 * </ul>
	 *
	 * @param registry the Spring MVC resource handler registry
	 * @since v2026.1.0
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
