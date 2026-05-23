package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Salesforce — leads, opportunities, accounts. Credentials: { "accessToken":"...", "instanceUrl":"https://yourorg.salesforce.com" } */
@Component
public class SalesforceConnector extends BaseConnector {
    @Override public String getType() { return "salesforce"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token = credStr(creds, "accessToken");
        String base  = credStr(creds, "instanceUrl").replaceAll("/$", "");
        String auth  = "Bearer " + token;
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            // Opportunities
            JsonNode opps = httpGet(base + "/services/data/v58.0/query?q=" + java.net.URLEncoder.encode("SELECT Id,Name,StageName,Amount,CloseDate,Description FROM Opportunity ORDER BY LastModifiedDate DESC LIMIT 100", "UTF-8"), auth);
            for (JsonNode r : opps.path("records")) {
                String id = r.path("Id").asText();
                String name = r.path("Name").asText();
                String url = base + "/" + id;
                String text = "# Opportunity: " + name + "\n**Stage:** " + r.path("StageName").asText() + "\n**Amount:** " + r.path("Amount").asText() + "\n**Close Date:** " + r.path("CloseDate").asText() + "\n\n" + r.path("Description").asText("");
                if (ingestDocument(connId, "opp-" + id, name, url, text)) synced++; else skipped++;
            }
            // Accounts
            JsonNode accts = httpGet(base + "/services/data/v58.0/query?q=" + java.net.URLEncoder.encode("SELECT Id,Name,Industry,Description FROM Account ORDER BY LastModifiedDate DESC LIMIT 100", "UTF-8"), auth);
            for (JsonNode r : accts.path("records")) {
                String id = r.path("Id").asText();
                String name = r.path("Name").asText();
                String text = "# Account: " + name + "\n**Industry:** " + r.path("Industry").asText() + "\n\n" + r.path("Description").asText("");
                if (ingestDocument(connId, "acct-" + id, name, base + "/" + id, text)) synced++; else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[salesforce] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { Map<String, Object> creds = parseCredentials(credentials); httpGet(credStr(creds, "instanceUrl") + "/services/data/v58.0/", "Bearer " + credStr(creds, "accessToken")); return true; } catch (Exception e) { return false; }
    }
}
