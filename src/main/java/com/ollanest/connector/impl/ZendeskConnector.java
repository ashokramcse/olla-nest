package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.Map;

/** Zendesk — tickets + help center articles. Credentials: { "email":"...", "apiToken":"...", "subdomain":"myco" } */
@Component
public class ZendeskConnector extends BaseConnector {
    @Override public String getType() { return "zendesk"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String sub    = credStr(creds, "subdomain");
        String auth   = "Basic " + Base64.getEncoder().encodeToString((credStr(creds, "email") + "/token:" + credStr(creds, "apiToken")).getBytes());
        String connId = (String) config.get("id");
        String base   = "https://" + sub + ".zendesk.com";
        int synced = 0, skipped = 0;
        try {
            JsonNode tickets = httpGet(base + "/api/v2/tickets.json?sort_by=updated_at&sort_order=desc&per_page=100", auth);
            for (JsonNode t : tickets.path("tickets")) {
                String id    = t.path("id").asText();
                String title = "#" + id + ": " + t.path("subject").asText();
                String url   = base + "/agent/tickets/" + id;
                String desc  = t.path("description").asText("");
                if (ingestDocument(connId, "ticket-" + id, title, url, "# " + title + "\n\n" + desc)) synced++; else skipped++;
            }
            // Help center articles
            try {
                JsonNode articles = httpGet(base + "/api/v2/help_center/articles.json?per_page=100", auth);
                for (JsonNode a : articles.path("articles")) {
                    String id    = a.path("id").asText();
                    String title = a.path("title").asText();
                    String url   = a.path("html_url").asText();
                    String body  = a.path("body").asText("").replaceAll("<[^>]+>", " ");
                    if (ingestDocument(connId, "article-" + id, title, url, "# " + title + "\n\n" + body)) synced++; else skipped++;
                }
            } catch (Exception ignore) {}
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[zendesk] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }

    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            String auth = "Basic " + Base64.getEncoder().encodeToString((credStr(creds, "email") + "/token:" + credStr(creds, "apiToken")).getBytes());
            httpGet("https://" + credStr(creds, "subdomain") + ".zendesk.com/api/v2/users/me.json", auth);
            return true;
        } catch (Exception e) { return false; }
    }
}
