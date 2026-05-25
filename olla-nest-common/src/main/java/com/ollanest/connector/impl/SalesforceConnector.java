package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Connector implementation that synchronises Salesforce CRM records into the
 * Olla knowledge base.
 *
 * <h3>Why this class exists</h3> Salesforce is the leading enterprise CRM
 * platform. This connector indexes Opportunities and Accounts via SOQL queries
 * so that the AI assistant can surface deal-pipeline and account information
 * alongside other organisational knowledge without requiring users to query
 * Salesforce directly.
 *
 * <h3>Credential format</h3> <pre>{@code { "accessToken": "...", // Salesforce
 * OAuth 2.0 session token. "instanceUrl": "https://yourorg.salesforce.com" //
 * Org-specific API base URL. } }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>All API calls target Salesforce REST API
 * version {@code v58.0}.</li> <li>Two SOQL queries are executed in sequence:
 * one for {@code Opportunity} records and one for {@code Account} records, each
 * ordered by {@code LastModifiedDate DESC} and limited to 100 rows.</li>
 * <li>Document IDs are namespaced ({@code opp-<id>} / {@code acct-<id>}) to
 * prevent collisions between the two object types.</li> <li>A trailing slash on
 * {@code instanceUrl} is stripped before constructing API URLs.</li> </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class SalesforceConnector extends BaseConnector {

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "salesforce"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "salesforce";
	}

	/**
	 * Synchronises Salesforce Opportunities and Accounts into the Olla knowledge
	 * base.
	 *
	 * <p>
	 * Two SOQL queries are executed in sequence:
	 * <ol>
	 * <li><b>Opportunities</b> — selects {@code Id}, {@code Name},
	 * {@code StageName}, {@code Amount}, {@code CloseDate}, and
	 * {@code Description}, ordered by {@code LastModifiedDate DESC}, limited to
	 * 100.</li>
	 * <li><b>Accounts</b> — selects {@code Id}, {@code Name}, {@code Industry}, and
	 * {@code Description}, ordered by {@code LastModifiedDate DESC}, limited to
	 * 100.</li>
	 * </ol>
	 * Each record is formatted as a lightweight Markdown document and passed to
	 * {@link BaseConnector#ingestDocument}.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string containing {@code "accessToken"} and
	 *                    {@code "instanceUrl"}.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         on failure.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "accessToken");
		String base = credStr(creds, "instanceUrl").replaceAll("/$", "");
		String auth = "Bearer " + token;
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			// Opportunities
			JsonNode opps = httpGet(base + "/services/data/v58.0/query?q="
					+ java.net.URLEncoder.encode("SELECT Id,Name,StageName,Amount,CloseDate,Description"
							+ " FROM Opportunity ORDER BY LastModifiedDate DESC LIMIT 100", "UTF-8"),
					auth);
			for (JsonNode r : opps.path("records")) {
				String id = r.path("Id").asText();
				String name = r.path("Name").asText();
				String url = base + "/" + id;
				String text = "# Opportunity: " + name + "\n**Stage:** " + r.path("StageName").asText()
						+ "\n**Amount:** " + r.path("Amount").asText() + "\n**Close Date:** "
						+ r.path("CloseDate").asText() + "\n\n" + r.path("Description").asText("");
				if (ingestDocument(connId, "opp-" + id, name, url, text))
					synced++;
				else
					skipped++;
			}
			// Accounts
			JsonNode accts = httpGet(base + "/services/data/v58.0/query?q=" + java.net.URLEncoder.encode(
					"SELECT Id,Name,Industry,Description" + " FROM Account ORDER BY LastModifiedDate DESC LIMIT 100",
					"UTF-8"), auth);
			for (JsonNode r : accts.path("records")) {
				String id = r.path("Id").asText();
				String name = r.path("Name").asText();
				String text = "# Account: " + name + "\n**Industry:** " + r.path("Industry").asText() + "\n\n"
						+ r.path("Description").asText("");
				if (ingestDocument(connId, "acct-" + id, name, base + "/" + id, text))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[salesforce] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully call the Salesforce
	 * REST API.
	 *
	 * <p>
	 * The test calls {@code GET /services/data/v58.0/} on the instance URL, which
	 * returns a list of available API resources and confirms that the access token
	 * is valid and the instance URL is reachable.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "accessToken"} and
	 *                    {@code "instanceUrl"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			httpGet(credStr(creds, "instanceUrl") + "/services/data/v58.0/", "Bearer " + credStr(creds, "accessToken"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
