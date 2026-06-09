package com.ollanest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Central Spring {@code @Configuration} class that declares application-wide
 * beans and exposes environment-driven configuration as typed accessors.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Spring Boot auto-configuration covers many concerns, but the application
 * still needs a single place to declare the shared {@link ObjectMapper} bean
 * and to bind startup properties (admin credentials, directory paths, version
 * string) to strongly-typed fields. Centralising these here keeps
 * {@code application.
 * properties} as the sole configuration surface and avoids scattered
 * {@code @Value} annotations in unrelated service classes.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A single {@code ObjectMapper} bean is declared here and injected
 * everywhere to ensure consistent JSON serialisation configuration across all
 * services and controllers.</li>
 * <li>Dates are serialised as ISO-8601 strings (not epoch milliseconds) by
 * disabling {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}.</li>
 * <li>All properties have safe defaults so the application starts in
 * development without any external configuration file.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@Configuration
public class AppConfig {

	/**
	 * Application semantic version string, shown in the UI and bootstrap response.
	 */
	@Value("${app.version:v2026.1.0}")
	private String appVersion;

	/** Default admin email used on first-boot account creation. */
	@Value("${app.default-admin-email:admin@ollanest.local}")
	private String defaultAdminEmail;

	/**
	 * Default admin password used on first-boot account creation. AuthService
	 * replaces this with a random password if the default value is detected at
	 * startup.
	 */
	@Value("${app.default-admin-password:CHANGE_ME_ON_FIRST_BOOT}")
	private String defaultAdminPassword;

	/** Default password assigned to demo/seed user accounts. */
	@Value("${app.default-user-password:CHANGE_ME_ON_FIRST_BOOT}")
	private String defaultUserPassword;

	/** Directory path for persistent data files (SQLite DB, backups). */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/** Directory path for static frontend assets served by {@code WebConfig}. */
	@Value("${app.static-dir:./public}")
	private String staticDir;

	/**
	 * Produces the shared {@link ObjectMapper} bean used across all services and
	 * controllers for JSON serialisation and deserialisation.
	 *
	 * <p>
	 * Configuration applied:
	 * <ul>
	 * <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} disabled so dates
	 * are emitted as ISO-8601 strings.</li>
	 * </ul>
	 *
	 * @return a configured, reusable {@link ObjectMapper} instance
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@Bean
	ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}

	/**
	 * Returns the application version string (e.g. {@code "v2026.1.0"}).
	 *
	 * @return the application version
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getAppVersion() {
		return appVersion;
	}

	/**
	 * Returns the default admin email for first-boot seeding.
	 *
	 * @return the default admin email address
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getDefaultAdminEmail() {
		return defaultAdminEmail;
	}

	/**
	 * Returns the default admin password for first-boot seeding. This value is
	 * replaced by a random password at startup if unchanged.
	 *
	 * @return the default admin password (plain text, used only at seed time)
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getDefaultAdminPassword() {
		return defaultAdminPassword;
	}

	/**
	 * Returns the default password assigned to new demo user accounts.
	 *
	 * @return the default user password
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getDefaultUserPassword() {
		return defaultUserPassword;
	}

	/**
	 * Returns the data directory path for the SQLite database and backup files.
	 *
	 * @return path to the data directory (relative or absolute)
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getDataDir() {
		return dataDir;
	}

	/**
	 * Returns the static directory path from which frontend assets are served.
	 *
	 * @return path to the public/static directory (relative or absolute)
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public String getStaticDir() {
		return staticDir;
	}
}
