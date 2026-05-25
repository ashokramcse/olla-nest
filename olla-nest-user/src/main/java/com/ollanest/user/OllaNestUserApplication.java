package com.ollanest.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Olla Nest Employee Workspace.
 *
 * <p>Serves the employee AI workspace on port 8081 (configurable via {@code USER_PORT}).
 * Scans the full {@code com.ollanest} package tree which includes beans from
 * {@code olla-nest-common} (services, config, filters) and this module's own
 * user-facing controllers.
 *
 * <p>Flyway is disabled here — the admin service ({@code olla-nest-admin})
 * owns schema migrations. Start admin first.
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
/**
 * Exclude all admin-only controllers — they live only in olla-nest-admin.
 * User controllers and UserPageController are in this module and are picked
 * up normally. AdminPageController is excluded via the admin package filter.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(
	basePackages = "com.ollanest",
	excludeFilters = @ComponentScan.Filter(
		type = FilterType.REGEX,
		pattern = "com\\.ollanest\\.(controller\\.admin\\..*|controller\\.AdminPageController|admin\\..*)"
	)
)
public class OllaNestUserApplication {

	public static void main(String[] args) {
		SpringApplication.run(OllaNestUserApplication.class, args);
	}
}
