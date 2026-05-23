package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Connector implementation that synchronises Zendesk support tickets and Help Center articles
 * into the Olla knowledge base.
 *
 * <h3>Why this class exists</h3>
 * Zendesk is a widely used customer support platform. Indexing tickets and Help Center articles
 * allows the AI assistant to answer questions about known issues, product troubleshooting
 * guides, and customer-facing documentation without requiring support agents or developers to
 * switch context into Zendesk.
 *
 * <h3>Credential format</h3>
 * <pre>{@code
 * {
 *   "email":     "agent@company.com",
 *   "apiToken":  "...",        // Zendesk API token (not the account password).
 *   "subdomain": "myco"       // The subdomain in https://myco.zendesk.com.
 * }
 * }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Authentication uses HTTP Basic with the format {@code email/token:apiToken}
 *       Base64-encoded, as required by the Zendesk API token auth scheme.</li>
 *   <li>Tickets are fetched sorted by {@code updated_at DESC} with up to 100 per page;
 *       cursor-based pagination is not yet implemented.</li>
 *   <li>Help Center articles are fetched from {@code /api/v2/help_center/articles.json}.
 *       Article bodies are HTML; tags are stripped via a simple regex to produce plain text.
 *       If the Help Center endpoint is unavailable (e.g. the plan does not include Guide),
 *       the exception is silently swallowed so that ticket sync still succeeds.</li>
 *   <li>Document IDs are namespaced ({@code ticket-<id>} / {@code article-<id>}) to prevent
 *       collisions between the two content types.</li>
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
public class ZendeskConnector extends BaseConnector {

    /**
     * Returns the connector type identifier used to look up this bean at runtime.
     *
     * @return {@code "zendesk"}
     * @since v2026.1.4
     */
    @Override
    public String getType() {
        return "zendesk";
    }

    /**
     * Synchronises Zendesk tickets and Help Center articles into the Olla knowledge base.
     *
     * <p>Two content types are synced in sequence:
     * <ol>
     *   <li><b>Tickets</b> — up to 100 tickets sorted by {@code updated_at DESC}. Each ticket's
     *       subject and description are formatted as a Markdown document.</li>
     *   <li><b>Help Center articles</b> — up to 100 articles. Article HTML bodies are stripped
     *       of tags before ingestion. Failures on the Help Center fetch (e.g. feature not
     *       enabled) are silently ignored so that ticket sync is not affected.</li>
     * </ol>
     *
     * @param config      connector configuration map; must contain {@code "id"} (connector ID).
     * @param credentials JSON string containing {@code "email"}, {@code "apiToken"}, and
     *                    {@code "subdomain"}.
     * @return a {@link SyncResult} with synced/skipped counts, or an error result if the
     *         ticket listing call fails.
     * @since v2026.1.4
     */
    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String sub    = credStr(creds, "subdomain");
        String auth   = "Basic " + Base64.getEncoder().encodeToString(
                (credStr(creds, "email") + "/token:" + credStr(creds, "apiToken")).getBytes());
        String connId = (String) config.get("id");
        String base   = "https://" + sub + ".zendesk.com";
        int synced = 0, skipped = 0;
        try {
            JsonNode tickets = httpGet(
                    base + "/api/v2/tickets.json?sort_by=updated_at&sort_order=desc&per_page=100",
                    auth);
            for (JsonNode t : tickets.path("tickets")) {
                String id    = t.path("id").asText();
                String title = "#" + id + ": " + t.path("subject").asText();
                String url   = base + "/agent/tickets/" + id;
                String desc  = t.path("description").asText("");
                if (ingestDocument(connId, "ticket-" + id, title, url, "# " + title + "\n\n" + desc)) {
                    synced++;
                } else {
                    skipped++;
                }
            }
            // Help center articles
            try {
                JsonNode articles = httpGet(
                        base + "/api/v2/help_center/articles.json?per_page=100",
                        auth);
                for (JsonNode a : articles.path("articles")) {
                    String id    = a.path("id").asText();
                    String title = a.path("title").asText();
                    String url   = a.path("html_url").asText();
                    String body  = a.path("body").asText("").replaceAll("<[^>]+>", " ");
                    if (ingestDocument(connId, "article-" + id, title, url, "# " + title + "\n\n" + body)) {
                        synced++;
                    } else {
                        skipped++;
                    }
                }
            } catch (Exception ignore) {}
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[zendesk] {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    /**
     * Validates that the supplied credentials can successfully authenticate against the Zendesk API.
     *
     * <p>The test calls {@code GET /api/v2/users/me.json}, which returns the authenticated
     * agent's user record and is the standard credential-verification endpoint for Zendesk.
     *
     * @param config      connector configuration map (not used by this implementation).
     * @param credentials JSON string containing {@code "email"}, {@code "apiToken"}, and
     *                    {@code "subdomain"}.
     * @return {@code true} if the API call succeeds without throwing; {@code false} otherwise.
     * @since v2026.1.4
     */
    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (credStr(creds, "email") + "/token:" + credStr(creds, "apiToken")).getBytes());
            httpGet("https://" + credStr(creds, "subdomain") + ".zendesk.com/api/v2/users/me.json",
                    auth);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
