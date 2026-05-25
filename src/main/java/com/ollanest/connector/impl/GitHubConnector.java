package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring {@code @Component} that pulls GitHub repository content into the
 * Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Most engineering knowledge lives in Git
 * repositories — README files contain onboarding guides and architecture
 * overviews, and open issues capture bugs, feature requests, and technical
 * debt. This connector fetches both artifact types so the AI layer can answer
 * developer questions such as "how do I set up the auth service?" or "what
 * issues are open around rate limiting?" without requiring manual copy-paste
 * from GitHub.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "token": "ghp_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"  // GitHub personal access token or
 *                                                        // fine-grained token with repo read scope
 * }
 * </pre>
 *
 * <h3>Configuration format</h3>
 * 
 * <pre>
 * {
 *   "owner": "myorg",    // GitHub organisation or user login — required
 *   "repo":  "myrepo"   // specific repository name — optional; omit to sync all org repos
 * }
 * </pre>
 * 
 * When {@code repo} is absent or blank, the connector lists all repositories in
 * the organisation and syncs each one.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>README content is fetched from the GitHub Contents API and decoded from
 * Base64 (MIME variant) before ingestion.</li>
 * <li>Pull requests are excluded from the issues sync by checking for the
 * presence of the {@code pull_request} field, which GitHub includes on issues
 * that are also PRs.</li>
 * <li>Each README is keyed by {@code owner/repo/README} and each issue by
 * {@code owner/repo/issue/number}, ensuring idempotent re-syncs.</li>
 * <li>If a repository has no README (e.g., newly created), the 404 from the
 * Contents API is silently ignored and the sync continues with issues.</li>
 * <li>Up to 50 repositories and 50 open issues per repository are fetched per
 * sync run; pagination is not yet implemented.</li>
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
public class GitHubConnector extends BaseConnector {

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals {@code "github"}.
	 *
	 * @return the string {@code "github"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "github";
	}

	/**
	 * Synchronises README files and open issues from the configured GitHub
	 * repository (or all organisation repositories) with the Olla-Nest document
	 * store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Determines the target repository set: a single repo if {@code repo} is
	 * configured, or all organisation repositories (up to 50) if it is blank.</li>
	 * <li>For each repository, attempts to fetch and ingest the README via the
	 * GitHub Contents API (Base64-decoded).</li>
	 * <li>Fetches open issues sorted by last-updated, skips pull requests, and
	 * ingests each issue as a Markdown document.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID) and {@code "config_json"} with at least
	 *                    {@code "owner"}
	 * @param credentials JSON string containing {@code "token"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the top-level API call fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "token");
		String auth = "Bearer " + token;
		Map<String, Object> cfg = parseConfig(config);
		String owner = credStr(cfg, "owner");
		String repo = credStr(cfg, "repo");
		String connId = (String) config.get("id");

		int synced = 0, skipped = 0;
		try {
			// Determine which repos to sync
			String reposUrl = repo.isBlank() ? "https://api.github.com/orgs/" + owner + "/repos?per_page=50&type=all"
					: "https://api.github.com/repos/" + owner + "/" + repo;

			JsonNode repoNode = httpGet(reposUrl, auth);
			JsonNode repos = repoNode.isArray() ? repoNode : mapper.createArrayNode().add(repoNode);

			for (JsonNode r : repos) {
				String rName = r.path("full_name").asText();
				String rUrl = r.path("html_url").asText();

				// README
				try {
					JsonNode rm = httpGet("https://api.github.com/repos/" + rName + "/readme", auth);
					String content = new String(
							java.util.Base64.getMimeDecoder().decode(rm.path("content").asText("")));
					if (ingestDocument(connId, rName + "/README", rName + " README", rUrl, content))
						synced++;
					else
						skipped++;
				} catch (Exception ignore) {
				}

				// Issues (open)
				JsonNode issues = httpGet(
						"https://api.github.com/repos/" + rName + "/issues?state=open&per_page=50&sort=updated", auth);
				for (JsonNode issue : issues) {
					if (issue.has("pull_request"))
						continue; // skip PRs here
					String title = issue.path("title").asText();
					String body = issue.path("body").asText("");
					String issueUrl = issue.path("html_url").asText();
					String extId = rName + "/issue/" + issue.path("number").asInt();
					String text = "# " + title + "\n\n" + body;
					if (ingestDocument(connId, extId, title, issueUrl, text))
						synced++;
					else
						skipped++;
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[github] sync failed: {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied personal access token can authenticate against the
	 * GitHub {@code /user} endpoint.
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
			httpGet("https://api.github.com/user", "Bearer " + credStr(creds, "token"));
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
