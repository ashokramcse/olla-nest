package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring {@code @Component} that pulls Figma file and component metadata into
 * the Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Design teams document components, patterns,
 * and usage guidelines inside Figma files. This connector walks all projects
 * visible to the authenticated user, fetches file names and component
 * descriptions for each file, and ingests the results so the AI layer can
 * answer questions like "what does the Button component do?" or "which project
 * owns the checkout flow?" without requiring designers to manually export
 * documentation.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "token": "figd_XXXXXXXXXXXXXXXXXXXXXXXXXXXX"  // Figma personal access token
 * }
 * </pre>
 *
 * <h3>Configuration format</h3> No additional {@code config_json} is required.
 * The connector automatically discovers all projects accessible to the
 * authenticated user via {@code GET /me/projects}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Component data is fetched via the {@code /files/:key/components}
 * endpoint. If this call fails (e.g., the file has no components, or the token
 * lacks full file access), the connector falls back to ingesting just the file
 * name and project name rather than aborting the sync entirely.</li>
 * <li>Each file is keyed by its Figma file key ({@code fileKey}), which is
 * stable across renames, so re-syncs replace rather than duplicate existing
 * documents.</li>
 * <li>Component descriptions are ingested as a bulleted list under the file
 * heading, making them directly searchable by component name or purpose.</li>
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
public class FigmaConnector extends BaseConnector {

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals {@code "figma"}.
	 *
	 * @return the string {@code "figma"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "figma";
	}

	/**
	 * Synchronises file names and component descriptions from all Figma projects
	 * accessible to the authenticated user with the Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Lists all projects visible to the token via
	 * {@code GET /me/projects}.</li>
	 * <li>For each project, lists all files via
	 * {@code GET /projects/:id/files}.</li>
	 * <li>For each file, attempts to fetch component metadata via
	 * {@code GET /files/:key/components} and builds a Markdown document listing
	 * each component name and description.</li>
	 * <li>If component fetching fails, falls back to a minimal document containing
	 * just the file name and project name.</li>
	 * <li>Ingests each file as a document keyed by its Figma file key.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID); {@code "config_json"} is not required
	 *                    for this connector
	 * @param credentials JSON string containing {@code "token"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the project listing fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String auth = "Bearer " + credStr(creds, "token");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode projects = httpGet("https://api.figma.com/v1/me/projects", auth);
			for (JsonNode proj : projects.path("projects")) {
				String projId = proj.path("id").asText();
				String projName = proj.path("name").asText();
				JsonNode files = httpGet("https://api.figma.com/v1/projects/" + projId + "/files", auth);
				for (JsonNode file : files.path("files")) {
					String fileKey = file.path("key").asText();
					String fileName = file.path("name").asText();
					String fileUrl = "https://www.figma.com/file/" + fileKey;
					// Get components
					try {
						JsonNode detail = httpGet("https://api.figma.com/v1/files/" + fileKey + "/components", auth);
						StringBuilder sb = new StringBuilder("# Figma: " + fileName + " (" + projName + ")\n\n");
						for (JsonNode c : detail.path("meta").path("components")) {
							sb.append("- ").append(c.path("name").asText()).append(": ")
									.append(c.path("description").asText("")).append("\n");
						}
						if (ingestDocument(connId, fileKey, fileName, fileUrl, sb.toString()))
							synced++;
						else
							skipped++;
					} catch (Exception ex) {
						// Fallback: just name + project
						if (ingestDocument(connId, fileKey, fileName, fileUrl,
								"# " + fileName + "\nProject: " + projName))
							synced++;
						else
							skipped++;
					}
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[figma] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied personal access token can authenticate against the
	 * Figma {@code /me} endpoint.
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
			httpGet("https://api.figma.com/v1/me", "Bearer " + credStr(parseCredentials(credentials), "token"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
