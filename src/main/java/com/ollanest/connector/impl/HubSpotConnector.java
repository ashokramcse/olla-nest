package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Connector implementation that synchronises CRM data from HubSpot into the
 * Olla knowledge base.
 *
 * <h3>Why this class exists</h3> HubSpot is a popular CRM platform used by
 * sales and marketing teams. This connector pulls contacts and deals into the
 * Olla vector store so that AI-assisted queries can surface relevant customer
 * and pipeline data alongside other enterprise content.
 *
 * <h3>Credential format</h3> <pre>{@code { "apiKey": "..." // HubSpot
 * private-app access token (Bearer token). } }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>Contacts are ingested with name, email, job
 * title, and company fields.</li> <li>Deals are ingested with deal name, stage,
 * amount, and close date.</li> <li>Each CRM record is given a namespaced
 * document ID ({@code contact-<id>} / {@code deal-<id>}) so that contacts and
 * deals cannot collide in the store.</li> <li>Results are capped at 100 per
 * object type; cursor-based pagination is not yet implemented.</li> </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class HubSpotConnector extends BaseConnector {

	/** Base URL for the HubSpot API. */
	private static final String BASE = "https://api.hubapi.com";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "hubspot"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "hubspot";
	}

	/**
	 * Synchronises HubSpot contacts and deals into the Olla knowledge base.
	 *
	 * <p>
	 * Two CRM object types are synced in sequence:
	 * <ol>
	 * <li><b>Contacts</b> — up to 100 records with {@code firstname},
	 * {@code lastname}, {@code email}, {@code jobtitle}, and {@code company}
	 * properties.</li>
	 * <li><b>Deals</b> — up to 100 records with {@code dealname}, {@code amount},
	 * {@code dealstage}, and {@code closedate} properties.</li>
	 * </ol>
	 * Each record is formatted as a lightweight Markdown document and passed to
	 * {@link BaseConnector#ingestDocument}.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"apiKey":"..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         on failure.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String auth = "Bearer " + credStr(creds, "apiKey");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			// Contacts
			JsonNode contacts = httpGet(
					BASE + "/crm/v3/objects/contacts?limit=100&properties=firstname,lastname,email,jobtitle,company",
					auth);
			for (JsonNode c : contacts.path("results")) {
				String id = c.path("id").asText();
				JsonNode p = c.path("properties");
				String name = p.path("firstname").asText("") + " " + p.path("lastname").asText("") + " — "
						+ p.path("company").asText("");
				String text = "# Contact: " + name.trim() + "\n**Email:** " + p.path("email").asText() + "\n**Title:** "
						+ p.path("jobtitle").asText();
				if (ingestDocument(connId, "contact-" + id, name.trim(), BASE + "/contacts/" + id, text)) {
					synced++;
				} else {
					skipped++;
				}
			}
			// Deals
			JsonNode deals = httpGet(
					BASE + "/crm/v3/objects/deals?limit=100&properties=dealname,amount,dealstage,closedate", auth);
			for (JsonNode d : deals.path("results")) {
				String id = d.path("id").asText();
				JsonNode p = d.path("properties");
				String name = p.path("dealname").asText("Deal " + id);
				String text = "# Deal: " + name + "\n**Stage:** " + p.path("dealstage").asText() + "\n**Amount:** "
						+ p.path("amount").asText() + "\n**Close Date:** " + p.path("closedate").asText();
				if (ingestDocument(connId, "deal-" + id, name, BASE + "/deals/" + id, text)) {
					synced++;
				} else {
					skipped++;
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[hubspot] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully call the HubSpot CRM
	 * API.
	 *
	 * <p>
	 * The test calls {@code GET /crm/v3/objects/contacts?limit=1}, which is the
	 * lightest authenticated request available on the CRM v3 endpoint.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "apiKey"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			httpGet(BASE + "/crm/v3/objects/contacts?limit=1",
					"Bearer " + credStr(parseCredentials(credentials), "apiKey"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
