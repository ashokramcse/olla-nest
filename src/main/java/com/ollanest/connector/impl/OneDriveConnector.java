package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** OneDrive / SharePoint via Microsoft Graph. Credentials: { "accessToken": "..." } */
@Component
public class OneDriveConnector extends BaseConnector {
    private static final String BASE = "https://graph.microsoft.com/v1.0";
    @Override public String getType() { return "onedrive"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "accessToken");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode items = httpGet(BASE + "/me/drive/root/children?$top=100&$select=id,name,webUrl,file,folder", auth);
            for (JsonNode item : items.path("value")) {
                if (!item.has("file")) continue;
                String id   = item.path("id").asText();
                String name = item.path("name").asText();
                String url  = item.path("webUrl").asText();
                String mime = item.path("file").path("mimeType").asText();
                if (!mime.contains("text") && !mime.contains("word") && !mime.contains("plain")) continue;
                try {
                    java.net.URI uri = java.net.URI.create(BASE + "/me/drive/items/" + id + "/content");
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder().uri(uri).header("Authorization", auth).timeout(java.time.Duration.ofSeconds(30)).GET().build();
                    java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (ingestDocument(connId, id, name, url, resp.body())) synced++; else skipped++;
                } catch (Exception ex) { log.warn("[onedrive] skip {}: {}", name, ex.getMessage()); }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[onedrive] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet(BASE + "/me", "Bearer " + credStr(parseCredentials(credentials), "accessToken")); return true; } catch (Exception e) { return false; }
    }
}
