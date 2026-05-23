package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.Map;

/** Bitbucket — repos and issues. Credentials: { "username":"...", "appPassword":"..." } Config: { "workspace":"..." } */
@Component
public class BitbucketConnector extends BaseConnector {
    private static final String BASE = "https://api.bitbucket.org/2.0";
    @Override public String getType() { return "bitbucket"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        Map<String, Object> cfg = parseConfig(config);
        String auth = "Basic " + Base64.getEncoder().encodeToString((credStr(creds, "username") + ":" + credStr(creds, "appPassword")).getBytes());
        String workspace = credStr(cfg, "workspace");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode repos = httpGet(BASE + "/repositories/" + workspace + "?pagelen=50", auth);
            for (JsonNode r : repos.path("values")) {
                String slug = r.path("slug").asText();
                String url  = r.path("links").path("html").path("href").asText();
                // Issues
                try {
                    JsonNode issues = httpGet(BASE + "/repositories/" + workspace + "/" + slug + "/issues?sort=-updated_on&pagelen=50", auth);
                    for (JsonNode i : issues.path("values")) {
                        String id    = i.path("id").asText();
                        String title = i.path("title").asText();
                        String body  = i.path("content").path("raw").asText("");
                        if (ingestDocument(connId, workspace + "/" + slug + "/issue/" + id, title, url + "/issues/" + id, "# " + title + "\n\n" + body)) synced++; else skipped++;
                    }
                } catch (Exception ignore) {}
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[bitbucket] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { Map<String, Object> creds = parseCredentials(credentials); httpGet(BASE + "/user", "Basic " + Base64.getEncoder().encodeToString((credStr(creds, "username") + ":" + credStr(creds, "appPassword")).getBytes())); return true; } catch (Exception e) { return false; }
    }
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); } catch (Exception e) { return Map.of(); }
    }
}
