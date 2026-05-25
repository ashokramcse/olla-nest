package com.ollanest.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Olla Nest Admin Control Panel.
 *
 * <p>Serves the admin dashboard on port 8080 (configurable via {@code ADMIN_PORT}).
 * Scans the full {@code com.ollanest} package tree which includes beans from
 * {@code olla-nest-common} (services, config, filters) and this module's own
 * admin controllers.
 *
 * <p>This service owns Flyway schema migrations — start it before the user
 * service so the schema is ready.
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
/**
 * Exclude all user-workspace controllers — they live only in olla-nest-user.
 * Admin controllers (com.ollanest.controller.admin.*) and AdminPageController
 * are picked up normally because they are in this module's own classpath.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(
	basePackages = "com.ollanest",
	excludeFilters = @ComponentScan.Filter(
		type = FilterType.REGEX,
		// State is in common (needed by admin for /api/state + /api/ollama/models)
		pattern = "com\\.ollanest\\.controller\\.(Chat|Thread|Document|Workspace|Voice|CodeSandbox|Image|Account|Sso|DevHints|Bootstrap|User).*Controller.*"
	)
)
public class OllaNestAdminApplication {

	public static void main(String[] args) {
		SpringApplication.run(OllaNestAdminApplication.class, args);
	}
}
