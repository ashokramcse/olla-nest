package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Spring {@code @Component} that pulls Bitbucket repository issues into the
 * Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Engineering teams that host code on Bitbucket
 * often use its native issue tracker for bug reports, feature requests, and
 * technical debt. This connector ingests those issues as Markdown documents so
 * that the AI layer can surface relevant issue context during developer Q&amp;A
 * without requiring a manual export.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "username":    "atlassian-account-username",
 *   "appPassword": "XXXXXXXXXXXXXXXXXXXXXXXX"   // Bitbucket app password (not the account password)
 * }
 * </pre>
 * 
 * Authentication is performed using HTTP Basic auth
 * ({@code username:appPassword} Base64-encoded).
 *
 * <h3>Configuration format</h3>
 * 
 * <pre>
 * {
 *   "workspace": "my-workspace-slug"   // Bitbucket workspace slug or UUID
 * }
 * </pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Up to 50 repositories are fetched per sync ({@code pagelen=50});
 * pagination is not yet implemented.</li>
 * <li>Issues are retrieved sorted by most-recently-updated
 * ({@code sort=-updated_on}) so that fresher context is prioritised on partial
 * syncs.</li>
 * <li>If the issues endpoint returns an error for a repository (e.g., issue
 * tracker disabled), that repository is silently skipped rather than aborting
 * the entire sync.</li>
 * <li>Each issue is keyed by {@code workspace/slug/issue/id} to ensure
 * uniqueness across repositories.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see com.ollanest.connector.BaseConnector
 */
@Component
public class BitbucketConnector extends BaseConnector {

	/** Root URL for the Bitbucket REST API v2.0. */
	private static final String BASE = "https://api.bitbucket.org/2.0";

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals
	 * {@code "bitbucket"}.
	 *
	 * @return the string {@code "bitbucket"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "bitbucket";
	}

	/**
	 * Synchronises issues from all repositories in the configured Bitbucket
	 * workspace with the Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Lists up to 50 repositories in the workspace.</li>
	 * <li>For each repository, retrieves up to 50 issues sorted by last-updated
	 * date.</li>
	 * <li>Ingests each issue as a Markdown document keyed by
	 * {@code workspace/slug/issue/id}.</li>
	 * </ol>
	 *
	 * <p>
	 * Repositories whose issue tracker is unavailable or restricted are silently
	 * skipped; their failure does not affect other repositories in the same run.
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID) and {@code "config_json"} with at least
	 *                    {@code "workspace"}
	 * @param credentials JSON string containing {@code "username"} and
	 *                    {@code "appPassword"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the top-level repository listing fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		Map<String, Object> cfg = parseConfig(config);
		String auth = "Basic " + Base64.getEncoder()
				.encodeToString((credStr(creds, "username") + ":" + credStr(creds, "appPassword")).getBytes());
		String workspace = credStr(cfg, "workspace");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode repos = httpGet(BASE + "/repositories/" + workspace + "?pagelen=50", auth);
			for (JsonNode r : repos.path("values")) {
				String slug = r.path("slug").asText();
				String url = r.path("links").path("html").path("href").asText();
				// Issues
				try {
					JsonNode issues = httpGet(
							BASE + "/repositories/" + workspace + "/" + slug + "/issues?sort=-updated_on&pagelen=50",
							auth);
					for (JsonNode i : issues.path("values")) {
						String id = i.path("id").asText();
						String title = i.path("title").asText();
						String body = i.path("content").path("raw").asText("");
						if (ingestDocument(connId, workspace + "/" + slug + "/issue/" + id, title,
								url + "/issues/" + id, "# " + title + "\n\n" + body))
							synced++;
						else
							skipped++;
					}
				} catch (Exception ignore) {
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[bitbucket] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied credentials can authenticate against the Bitbucket
	 * {@code /user} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "username"} and
	 *                    {@code "appPassword"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			httpGet(BASE + "/user", "Basic " + Base64.getEncoder()
					.encodeToString((credStr(creds, "username") + ":" + credStr(creds, "appPassword")).getBytes()));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Parses the {@code config_json} field from the connector row into a typed map.
	 *
	 * <p>
	 * Returns an empty map if the field is absent or contains malformed JSON,
	 * allowing callers to degrade gracefully rather than throw.
	 *
	 * @param config connector row map; must contain {@code "config_json"} as a JSON
	 *               string
	 * @return a {@code Map<String, Object>} representing the parsed configuration,
	 *         never {@code null}; empty map on parse failure
	 * @since v2026.1.4
	 */
	private Map<String, Object> parseConfig(Map<String, Object> config) {
		String json = (String) config.getOrDefault("config_json", "{}");
		try {
			return mapper.readValue(json,
					mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		} catch (Exception e) {
			return Map.of();
		}
	}
}
