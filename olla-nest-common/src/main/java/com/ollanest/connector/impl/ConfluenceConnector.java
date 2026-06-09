package com.ollanest.connector.impl;

import java.util.Base64;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;

/**
 * Spring {@code @Component} that pulls Confluence pages into the Olla-Nest
 * knowledge index.
 *
 * <h3>Why this class exists</h3> Confluence is the canonical long-form
 * documentation store for many engineering and product teams. This connector
 * fetches the 50 most-recently-modified pages across all spaces, strips the
 * HTML markup from the page body, and ingests the resulting plain text so the
 * AI layer can answer questions about internal runbooks, architecture
 * decisions, and how-to guides without requiring users to copy-paste content
 * manually.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "email":    "you@company.com",
 *   "apiToken": "ATATT3xFfGF0...",                        // Atlassian API token
 *   "baseUrl":  "https://mycompany.atlassian.net/wiki"    // trailing slash is stripped automatically
 * }
 * </pre>
 * 
 * Authentication uses HTTP Basic auth with {@code email:apiToken} encoded in
 * Base64.
 *
 * <h3>Configuration format</h3> No additional {@code config_json} is required.
 * Space and page discovery is automatic.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Pages are fetched with {@code expand=body.view,space,version} so the HTML
 * body, space name, and version metadata are all available in a single
 * request.</li>
 * <li>HTML tags are removed with a simple regex ({@code <[^>]+>}) and
 * whitespace is collapsed. This is intentionally lightweight — a full HTML
 * parser is not imported to avoid the extra dependency.</li>
 * <li>Each page is keyed by its numeric Confluence page ID, making re-syncs
 * idempotent.</li>
 * <li>Only {@code type=page} content is returned; blog posts and comments are
 * excluded by the API query.</li>
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
public class ConfluenceConnector extends BaseConnector {

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals
	 * {@code "confluence"}.
	 *
	 * @return the string {@code "confluence"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "confluence";
	}

	/**
	 * Synchronises the 50 most-recently-modified Confluence pages with the
	 * Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Queries {@code /rest/api/content} for the 50 most-recently-modified pages
	 * across all spaces with the page body, space name, and version metadata
	 * expanded.</li>
	 * <li>Strips HTML tags from the page body using a regex replacement.</li>
	 * <li>Ingests each page as a Markdown document keyed by the Confluence page
	 * ID.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID); {@code "config_json"} is not required
	 *                    for this connector
	 * @param credentials JSON string containing {@code "email"},
	 *                    {@code "apiToken"}, and {@code "baseUrl"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the API call fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
		String auth = "Basic " + Base64.getEncoder()
				.encodeToString((credStr(creds, "email") + ":" + credStr(creds, "apiToken")).getBytes());
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;

		try {
			JsonNode pages = httpGet(baseUrl
					+ "/rest/api/content?type=page&limit=50&expand=body.view,space,version&orderby=modified+desc",
					auth);
			for (JsonNode page : pages.path("results")) {
				String pageId = page.path("id").asText();
				String title = page.path("title").asText();
				String space = page.path("space").path("name").asText();
				String pageUrl = baseUrl + page.path("_links").path("webui").asText();
				String html = page.path("body").path("view").path("value").asText();
				// Strip HTML tags for plain text
				String text = "# " + title + " [" + space + "]\n\n"
						+ html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
				if (ingestDocument(connId, pageId, title, pageUrl, text))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[confluence] sync failed: {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied credentials can authenticate against the
	 * Confluence {@code /rest/api/space?limit=1} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "email"},
	 *                    {@code "apiToken"}, and {@code "baseUrl"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
			String auth = "Basic " + Base64.getEncoder()
					.encodeToString((credStr(creds, "email") + ":" + credStr(creds, "apiToken")).getBytes());
			httpGet(baseUrl + "/rest/api/space?limit=1", auth);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
