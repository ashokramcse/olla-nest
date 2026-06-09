package com.ollanest.connector.impl;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;

/**
 * Spring {@code @Component} that pulls GitLab project content into the
 * Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Teams that self-host or use GitLab.com keep
 * engineering knowledge in issues and README files that should be searchable
 * via the AI layer. This connector discovers all projects the token has
 * membership access to, ingests open issues, and fetches the {@code README.md}
 * from the default branch so that the AI layer can answer questions about
 * project purpose, setup, and active work without requiring users to manually
 * export content.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "token": "glpat-XXXXXXXXXXXXXXXXXXXX"  // GitLab personal access token with api scope
 * }
 * </pre>
 *
 * <h3>Configuration format</h3>
 * 
 * <pre>
 * {
 *   "baseUrl": "https://gitlab.mycompany.com"  // optional; defaults to https://gitlab.com
 *                                               // trailing slash is stripped automatically
 * }
 * </pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Projects are discovered via
 * {@code /api/v4/projects?membership=true&per_page=30} so only projects the
 * token's user is a member of are synced.</li>
 * <li>Issues are fetched with {@code state=opened} to keep the knowledge index
 * focused on actionable, current work.</li>
 * <li>The README is fetched as raw text via the repository files API using
 * {@code README%2Emd} as the URL-encoded filename and {@code ref=HEAD} to
 * follow the default branch automatically.</li>
 * <li>Missing READMEs (404) are silently ignored rather than aborting the
 * project sync.</li>
 * <li>Issues are keyed by {@code gl-<id>} and READMEs by
 * {@code gl-readme-<projectId>} to ensure global uniqueness across
 * projects.</li>
 * <li>The {@code testConnection} method always targets {@code gitlab.com}
 * regardless of the configured {@code baseUrl}; this is a known limitation and
 * may be refined in a future version.</li>
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
public class GitLabConnector extends BaseConnector {

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals {@code "gitlab"}.
	 *
	 * @return the string {@code "gitlab"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "gitlab";
	}

	/**
	 * Synchronises open issues and README files from all member GitLab projects
	 * with the Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Lists up to 30 projects the token's user is a member of via
	 * {@code GET /api/v4/projects?membership=true}.</li>
	 * <li>For each project, fetches up to 50 open issues and ingests each as a
	 * Markdown document keyed by {@code gl-<issueId>}.</li>
	 * <li>Attempts to fetch the project's {@code README.md} from HEAD and ingests
	 * it keyed by {@code gl-readme-<projectId>}; 404s are silently ignored.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID); {@code "config_json"} may optionally
	 *                    specify {@code "baseUrl"}
	 * @param credentials JSON string containing {@code "token"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the project listing fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "token");
		String base = credStr(parseConfig(config), "baseUrl");
		if (base.isBlank())
			base = "https://gitlab.com";
		base = base.replaceAll("/$", "");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode projects = httpGet(base + "/api/v4/projects?membership=true&per_page=30", "Bearer " + token);
			for (JsonNode p : projects) {
				int pid = p.path("id").asInt();
				String name = p.path("name_with_namespace").asText();
				String url = p.path("web_url").asText();
				JsonNode issues = httpGet(base + "/api/v4/projects/" + pid + "/issues?state=opened&per_page=50",
						"Bearer " + token);
				for (JsonNode i : issues) {
					String title = i.path("title").asText();
					String body = i.path("description").asText("");
					String iUrl = i.path("web_url").asText();
					if (ingestDocument(connId, "gl-" + i.path("id").asInt(), title, iUrl, "# " + title + "\n\n" + body))
						synced++;
					else
						skipped++;
				}
				// README
				try {
					JsonNode rm = httpGet(
							base + "/api/v4/projects/" + pid + "/repository/files/README%2Emd/raw?ref=HEAD",
							"Bearer " + token);
					if (ingestDocument(connId, "gl-readme-" + pid, name + " README", url, rm.asText()))
						synced++;
					else
						skipped++;
				} catch (Exception ignore) {
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[gitlab] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied personal access token can authenticate against the
	 * public GitLab.com {@code /api/v4/user} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "token"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			httpGet("https://gitlab.com/api/v4/user", "Bearer " + credStr(creds, "token"));
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
