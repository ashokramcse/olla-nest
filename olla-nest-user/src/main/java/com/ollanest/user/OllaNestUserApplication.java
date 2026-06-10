package com.ollanest.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Olla Nest Employee Workspace service.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Bootstraps and serves the employee-facing AI workspace on port 8081
 * (configurable via {@code USER_PORT}). It component-scans the full
 * {@code com.ollanest} package tree, which pulls in the shared beans from
 * {@code olla-nest-common} (services, config, filters) together with this
 * module's own user-facing controllers.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link ComponentScan} exclude filter removes all admin-only
 * controllers ({@code controller.admin.*}, {@code AdminPageController}, and the
 * {@code admin.*} package) — those beans live only in {@code olla-nest-admin} —
 * while keeping the user controllers and {@code UserPageController}.</li>
 * <li>Flyway is disabled in this service: the admin service
 * ({@code olla-nest-admin}) owns schema migrations, so the admin service must be
 * started first.</li>
 * <li>{@link EnableScheduling} activates the productivity schedulers (task
 * scheduler, connector sync) hosted in the common module.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.5 — initial split of the user service into its own Spring Boot
 * application with the admin-controller exclude filter.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.5
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.ollanest", excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.ollanest\\.(controller\\.admin\\..*|controller\\.AdminPageController|admin\\..*)"))
public class OllaNestUserApplication {

	/**
	 * Process entry point — boots the Spring application context and starts the
	 * embedded web server for the employee workspace.
	 *
	 * @param args standard JVM command-line arguments, forwarded to
	 *             {@link SpringApplication#run(Class, String...)} (e.g.
	 *             {@code --server.port=8081})
	 * @author Ashok Ram
	 * @since v2026.1.5
	 * @version v2026.1.5
	 */
	public static void main(String[] args) {
		SpringApplication.run(OllaNestUserApplication.class, args);
	}
}
