package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Dropbox — files and folders. Credentials: { "accessToken": "..." } */
@Component
public class DropboxConnector extends BaseConnector {
    @Override public String getType() { return "dropbox"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "accessToken");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode list = httpPost("https://api.dropboxapi.com/2/files/list_folder", auth, Map.of("path", "", "recursive", true, "limit", 100));
            for (JsonNode entry : list.path("entries")) {
                String tag = entry.path(".tag").asText();
                if (!"file".equals(tag)) continue;
                String path = entry.path("path_lower").asText();
                String name = entry.path("name").asText();
                String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
                if (!java.util.Set.of("txt", "md", "csv", "json").contains(ext)) continue;
                try {
                    java.net.URI uri = java.net.URI.create("https://content.dropboxapi.com/2/files/download");
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder().uri(uri)
                            .header("Authorization", auth)
                            .header("Dropbox-API-Arg", "{\"path\":\"" + path + "\"}")
                            .timeout(java.time.Duration.ofSeconds(30)).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
                    java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (ingestDocument(connId, path, name, "https://www.dropbox.com/home" + path, resp.body())) synced++; else skipped++;
                } catch (Exception ex) { log.warn("[dropbox] skip {}: {}", name, ex.getMessage()); }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[dropbox] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpPost("https://api.dropboxapi.com/2/users/get_current_account", "Bearer " + credStr(parseCredentials(credentials), "accessToken"), Map.of()); return true; } catch (Exception e) { return false; }
    }
}
