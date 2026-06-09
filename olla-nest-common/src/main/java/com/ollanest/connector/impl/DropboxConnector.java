package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Spring {@code @Component} that pulls plain-text files from Dropbox into the
 * Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Teams often keep important reference documents
 * — notes, specs, data exports — in Dropbox folders. This connector recursively
 * walks the user's entire Dropbox root, identifies files with text-compatible
 * extensions, downloads each file's raw content, and ingests it so the AI layer
 * can answer questions about file contents without requiring the user to
 * copy-paste anything.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "accessToken": "sl.XXXXXXXXXXXXXXXXXXXXXXXXXXXX"  // Dropbox OAuth2 access token
 * }
 * </pre>
 *
 * <h3>Configuration format</h3> No additional {@code config_json} is required.
 * The connector always starts from the Dropbox root folder ({@code ""}) with
 * recursive listing enabled.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Only files with extensions {@code txt}, {@code md}, {@code csv}, or
 * {@code json} are downloaded; all other file types (binary, images, etc.) are
 * skipped to avoid ingesting non-textual content into the embedding
 * pipeline.</li>
 * <li>File content is downloaded via the Dropbox Content API
 * ({@code content.dropboxapi.com/2/files/download}) using a separate HTTP call
 * from the metadata listing, as the standard API does not return file content
 * inline.</li>
 * <li>Each file is keyed by its lower-case Dropbox path ({@code path_lower}),
 * so re-syncs replace rather than duplicate existing documents.</li>
 * <li>Download errors for individual files are logged at WARN level and
 * skipped; the rest of the sync continues normally.</li>
 * <li>The download request uses a 30-second timeout to handle large files
 * without indefinitely blocking the sync thread.</li>
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
public class DropboxConnector extends BaseConnector {

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals {@code "dropbox"}.
	 *
	 * @return the string {@code "dropbox"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "dropbox";
	}

	/**
	 * Synchronises all plain-text files from the user's Dropbox root (recursively)
	 * with the Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Lists up to 100 entries from the Dropbox root folder recursively via the
	 * {@code /files/list_folder} API.</li>
	 * <li>Filters to file entries ({@code .tag == "file"}) whose extension is one
	 * of {@code txt}, {@code md}, {@code csv}, or {@code json}.</li>
	 * <li>Downloads each qualifying file's content via the Dropbox Content
	 * API.</li>
	 * <li>Ingests each file as a document keyed by its lower-case Dropbox
	 * path.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID); {@code "config_json"} is not required
	 *                    for this connector
	 * @param credentials JSON string containing {@code "accessToken"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the folder listing fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String auth = "Bearer " + credStr(creds, "accessToken");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode list = httpPost("https://api.dropboxapi.com/2/files/list_folder", auth,
					Map.of("path", "", "recursive", true, "limit", 100));
			for (JsonNode entry : list.path("entries")) {
				String tag = entry.path(".tag").asText();
				if (!"file".equals(tag))
					continue;
				String path = entry.path("path_lower").asText();
				String name = entry.path("name").asText();
				String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
				if (!Set.of("txt", "md", "csv", "json").contains(ext))
					continue;
				try {
					URI uri = URI.create("https://content.dropboxapi.com/2/files/download");
					HttpRequest req = HttpRequest.newBuilder().uri(uri)
							.header("Authorization", auth).header("Dropbox-API-Arg", "{\"path\":\"" + path + "\"}")
							.timeout(Duration.ofSeconds(30))
							.POST(HttpRequest.BodyPublishers.noBody()).build();
					HttpResponse<String> resp = http.send(req,
							HttpResponse.BodyHandlers.ofString());
					if (ingestDocument(connId, path, name, "https://www.dropbox.com/home" + path, resp.body()))
						synced++;
					else
						skipped++;
				} catch (Exception ex) {
					log.warn("[dropbox] skip {}: {}", name, ex.getMessage());
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[dropbox] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied access token can authenticate against the Dropbox
	 * {@code /users/get_current_account} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "accessToken"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			httpPost("https://api.dropboxapi.com/2/users/get_current_account",
					"Bearer " + credStr(parseCredentials(credentials), "accessToken"), Map.of());
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
