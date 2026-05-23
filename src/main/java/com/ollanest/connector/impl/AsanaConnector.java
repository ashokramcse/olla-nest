package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Spring {@code @Component} that pulls Asana tasks into the Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3>
 * Teams that live in Asana store a wealth of project context — task descriptions, acceptance
 * criteria, and status notes — that should be searchable via the AI layer. This connector
 * enumerates every workspace the token has access to, then pages through tasks in each workspace
 * and ingests them as Markdown documents so they appear in search results alongside code and docs.
 *
 * <h3>Credential format</h3>
 * <pre>
 * {
 *   "token": "1/XXXXXXXXXX:YYYYYYYYYYYYYYYYYY"  // Asana personal access token
 * }
 * </pre>
 *
 * <h3>Configuration format</h3>
 * No additional configuration is required. The connector automatically discovers all workspaces
 * visible to the supplied token.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Workspace discovery uses {@code GET /workspaces} so no workspace IDs need to be
 *       hard-coded in the connector configuration.</li>
 *   <li>Tasks are fetched with {@code opt_fields=gid,name,notes,permalink_url} to minimise
 *       response payload size; the {@code notes} field is used as the document body.</li>
 *   <li>Up to 100 tasks are retrieved per workspace in a single request ({@code limit=100});
 *       pagination is not yet implemented.</li>
 *   <li>Each task is keyed by its {@code gid} so repeat syncs are idempotent.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.4
 * @version v2026.1.4
 * @see     com.ollanest.connector.BaseConnector
 */
@Component
public class AsanaConnector extends BaseConnector {

    /** Root URL for the Asana REST API v1.0. */
    private static final String BASE = "https://app.asana.com/api/1.0";

    /**
     * Returns the connector-type discriminator used to match this implementation to a persisted
     * connector row whose {@code type} column equals {@code "asana"}.
     *
     * @return the string {@code "asana"}
     * @since v2026.1.4
     */
    @Override
    public String getType() {
        return "asana";
    }

    /**
     * Synchronises all tasks from all workspaces visible to the supplied token with the
     * Olla-Nest document store.
     *
     * <p>The method performs the following steps:
     * <ol>
     *   <li>Lists all workspaces accessible by the token via {@code GET /workspaces}.</li>
     *   <li>For each workspace, fetches up to 100 tasks with name, notes, and permalink.</li>
     *   <li>Ingests each task as a Markdown document keyed by the task's {@code gid}.</li>
     * </ol>
     *
     * @param  config      connector row from the database; must contain {@code "id"} (connector
     *                     UUID); {@code "config_json"} is not required for this connector
     * @param  credentials JSON string containing {@code "token"}
     * @return {@link SyncResult} with counts of synced and skipped documents, or an error message
     *         if the API call fails
     * @since v2026.1.4
     */
    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "token");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode ws = httpGet(BASE + "/workspaces", auth);
            for (JsonNode w : ws.path("data")) {
                String wsId = w.path("gid").asText();
                JsonNode tasks = httpGet(BASE + "/tasks?workspace=" + wsId + "&opt_fields=gid,name,notes,permalink_url&limit=100", auth);
                for (JsonNode t : tasks.path("data")) {
                    String gid   = t.path("gid").asText();
                    String name  = t.path("name").asText();
                    String url   = t.path("permalink_url").asText();
                    String notes = t.path("notes").asText("");
                    if (ingestDocument(connId, gid, name, url, "# " + name + "\n\n" + notes)) synced++;
                    else skipped++;
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[asana] {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    /**
     * Verifies that the supplied personal access token can authenticate against the Asana
     * {@code /users/me} endpoint.
     *
     * @param  config      connector configuration map (not used for this check)
     * @param  credentials JSON string containing {@code "token"}
     * @return {@code true} if the HTTP call succeeds without an exception; {@code false} otherwise
     * @since v2026.1.4
     */
    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            httpGet(BASE + "/users/me", "Bearer " + credStr(parseCredentials(credentials), "token"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
