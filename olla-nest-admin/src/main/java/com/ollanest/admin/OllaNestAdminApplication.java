package com.ollanest.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Olla Nest Admin Control Panel service.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Bootstraps and serves the admin dashboard on port 8080 (configurable via
 * {@code ADMIN_PORT}). It component-scans the full {@code com.ollanest} package
 * tree, which pulls in the shared beans from {@code olla-nest-common} (services,
 * config, filters) together with this module's own admin controllers.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link ComponentScan} exclude filter removes all user-workspace
 * controllers (chat, thread, document, workspace, voice, sandbox, image,
 * account, SSO, bootstrap, user) — those beans live only in
 * {@code olla-nest-user} — while keeping the admin controllers and
 * {@code AdminPageController}.</li>
 * <li>This service <b>owns Flyway schema migrations</b>, so it must be started
 * before the user service to ensure the schema is ready.</li>
 * <li>{@link EnableScheduling} activates the admin-side schedulers (connector
 * sync, background jobs) hosted in the common module.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.5 — initial split of the admin service into its own Spring Boot
 * application with the user-controller exclude filter.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.5
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.ollanest", excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
		// Exclude all user-workspace controllers (chat/thread live on user service
		// only).
		pattern = "com\\.ollanest\\.controller\\.(Chat|Thread|Document|Workspace|Voice|CodeSandbox|Image|Account|Sso|Bootstrap|User).*Controller.*"))
public class OllaNestAdminApplication {

	/**
	 * Process entry point — boots the Spring application context, applies Flyway
	 * migrations, and starts the embedded web server for the admin control panel.
	 *
	 * @param args standard JVM command-line arguments, forwarded to
	 *             {@link SpringApplication#run(Class, String...)} (e.g.
	 *             {@code --server.port=8080})
	 * @author Ashok Ram
	 * @since v2026.1.5
	 * @version v2026.1.5
	 */
	public static void main(String[] args) {
		SpringApplication.run(OllaNestAdminApplication.class, args);
	}
}
