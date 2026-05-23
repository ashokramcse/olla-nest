package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** HubSpot — contacts, deals, notes. Credentials: { "apiKey": "..." } */
@Component
public class HubSpotConnector extends BaseConnector {
    private static final String BASE = "https://api.hubapi.com";
    @Override public String getType() { return "hubspot"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "apiKey");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            // Contacts
            JsonNode contacts = httpGet(BASE + "/crm/v3/objects/contacts?limit=100&properties=firstname,lastname,email,jobtitle,company", auth);
            for (JsonNode c : contacts.path("results")) {
                String id = c.path("id").asText();
                JsonNode p = c.path("properties");
                String name = p.path("firstname").asText("") + " " + p.path("lastname").asText("") + " — " + p.path("company").asText("");
                String text = "# Contact: " + name.trim() + "\n**Email:** " + p.path("email").asText() + "\n**Title:** " + p.path("jobtitle").asText();
                if (ingestDocument(connId, "contact-" + id, name.trim(), BASE + "/contacts/" + id, text)) synced++; else skipped++;
            }
            // Deals
            JsonNode deals = httpGet(BASE + "/crm/v3/objects/deals?limit=100&properties=dealname,amount,dealstage,closedate", auth);
            for (JsonNode d : deals.path("results")) {
                String id = d.path("id").asText();
                JsonNode p = d.path("properties");
                String name = p.path("dealname").asText("Deal " + id);
                String text = "# Deal: " + name + "\n**Stage:** " + p.path("dealstage").asText() + "\n**Amount:** " + p.path("amount").asText() + "\n**Close Date:** " + p.path("closedate").asText();
                if (ingestDocument(connId, "deal-" + id, name, BASE + "/deals/" + id, text)) synced++; else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[hubspot] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet(BASE + "/crm/v3/objects/contacts?limit=1", "Bearer " + credStr(parseCredentials(credentials), "apiKey")); return true; } catch (Exception e) { return false; }
    }
}
