package com.ollanest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Olla Nest application.
 *
 * <p>
 * Bootstraps the entire application context via
 * {@link SpringApplication#run(Class, String[])}. The
 * {@link SpringBootApplication} annotation enables component scanning,
 * auto-configuration, and the {@code @Configuration} bean factory for the
 * {@code com.ollanest} package tree. {@link EnableScheduling} activates the
 * Spring task scheduler used by {@code BackupService} and {@code ModelService}
 * for their periodic jobs.
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>Scheduling is enabled at the application level rather than per-service to
 * keep the scheduled annotation processing in a single, predictable
 * location.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0 — initial Java Spring Boot migration
 */
@SpringBootApplication
@EnableScheduling
public class OllaNestApplication {

	/**
	 * Application entry point.
	 *
	 * <p>
	 * Delegates directly to {@link SpringApplication#run} which boots the embedded
	 * Tomcat server, initialises the Spring context, runs {@code DatabaseService}'s
	 * {@code @PostConstruct} pragmas, and starts all scheduled tasks.
	 *
	 * @param args command-line arguments forwarded to Spring (e.g.
	 *             {@code --server.port=8080})
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public static void main(String[] args) {
		SpringApplication.run(OllaNestApplication.class, args);
	}
}
