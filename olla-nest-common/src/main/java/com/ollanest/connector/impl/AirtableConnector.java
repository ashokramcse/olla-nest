package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Spring {@code @Component} that pulls Airtable base data into the Olla-Nest
 * knowledge index.
 *
 * <h3>Why this class exists</h3> Airtable is widely used as a lightweight
 * database / project-tracker. This connector walks every table inside a
 * specified base, fetches up to 100 records per table, and ingests each table
 * as a single Markdown document so that the AI search layer can answer
 * questions about structured data stored in Airtable without requiring users to
 * export CSVs manually.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "apiKey": "patXXXXXXXXXXXXXX"   // Airtable personal access token
 * }
 * </pre>
 *
 * <h3>Configuration format</h3>
 * 
 * <pre>
 * {
 *   "baseId": "appXXXXXXXXXXXXXX"   // Airtable base ID (visible in API docs URL)
 * }
 * </pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The Airtable Metadata API ({@code /meta/bases/:baseId/tables}) is used to
 * discover all tables without requiring the caller to enumerate them.</li>
 * <li>Table names are URL-encoded before being used as path segments in the
 * Records API.</li>
 * <li>Each table is stored as one document keyed by {@code baseId/tableId}, so
 * re-syncs replace rather than duplicate existing content.</li>
 * <li>Records are capped at 100 per table ({@code maxRecords=100}) to stay
 * within reasonable document-size limits for the embedding pipeline.</li>
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
public class AirtableConnector extends BaseConnector {

	/** Root URL for the Airtable REST API v0. */
	private static final String BASE = "https://api.airtable.com/v0";

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals
	 * {@code "airtable"}.
	 *
	 * @return the string {@code "airtable"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "airtable";
	}

	/**
	 * Synchronises all tables in the configured Airtable base with the Olla-Nest
	 * document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Calls the Airtable Metadata API to list all tables in the base.</li>
	 * <li>For each table, fetches up to 100 records via the Records API.</li>
	 * <li>Serialises each record's fields to Markdown and ingests the result as a
	 * single document keyed by {@code baseId/tableId}.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID) and {@code "config_json"} with at least
	 *                    {@code "baseId"}
	 * @param credentials JSON string containing {@code "apiKey"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the API call fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		Map<String, Object> cfg = parseConfig(config);
		String auth = "Bearer " + credStr(creds, "apiKey");
		String baseId = credStr(cfg, "baseId");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode meta = httpGet("https://api.airtable.com/v0/meta/bases/" + baseId + "/tables", auth);
			for (JsonNode table : meta.path("tables")) {
				String tableId = table.path("id").asText();
				String tableName = table.path("name").asText();
				JsonNode records = httpGet(
						BASE + "/" + baseId + "/" + java.net.URLEncoder.encode(tableName, "UTF-8") + "?maxRecords=100",
						auth);
				StringBuilder sb = new StringBuilder("# Table: " + tableName + "\n\n");
				for (JsonNode r : records.path("records")) {
					sb.append("---\n");
					r.path("fields").properties().forEach(e -> sb.append("**").append(e.getKey()).append(":** ")
							.append(e.getValue().asText()).append("\n"));
				}
				if (ingestDocument(connId, baseId + "/" + tableId, tableName, "https://airtable.com/" + baseId,
						sb.toString()))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[airtable] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied API key can authenticate against the Airtable
	 * {@code /meta/whoami} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "apiKey"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			httpGet("https://api.airtable.com/v0/meta/whoami",
					"Bearer " + credStr(parseCredentials(credentials), "apiKey"));
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
