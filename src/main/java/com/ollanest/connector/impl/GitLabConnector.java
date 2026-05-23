package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** GitLab connector — repos, issues, MRs. Credentials: { "token": "glpat-..." } Config: { "baseUrl": "https://gitlab.com" } */
@Component
public class GitLabConnector extends BaseConnector {
    @Override public String getType() { return "gitlab"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token   = credStr(creds, "token");
        String base    = credStr(parseConfig(config), "baseUrl");
        if (base.isBlank()) base = "https://gitlab.com";
        base = base.replaceAll("/$", "");
        String connId  = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode projects = httpGet(base + "/api/v4/projects?membership=true&per_page=30", "Bearer " + token);
            for (JsonNode p : projects) {
                int pid = p.path("id").asInt();
                String name = p.path("name_with_namespace").asText();
                String url  = p.path("web_url").asText();
                JsonNode issues = httpGet(base + "/api/v4/projects/" + pid + "/issues?state=opened&per_page=50", "Bearer " + token);
                for (JsonNode i : issues) {
                    String title = i.path("title").asText();
                    String body  = i.path("description").asText("");
                    String iUrl  = i.path("web_url").asText();
                    if (ingestDocument(connId, "gl-" + i.path("id").asInt(), title, iUrl, "# " + title + "\n\n" + body)) synced++; else skipped++;
                }
                // README
                try {
                    JsonNode rm = httpGet(base + "/api/v4/projects/" + pid + "/repository/files/README%2Emd/raw?ref=HEAD", "Bearer " + token);
                    if (ingestDocument(connId, "gl-readme-" + pid, name + " README", url, rm.asText())) synced++; else skipped++;
                } catch (Exception ignore) {}
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[gitlab] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }

    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { Map<String, Object> creds = parseCredentials(credentials); httpGet("https://gitlab.com/api/v4/user", "Bearer " + credStr(creds, "token")); return true; } catch (Exception e) { return false; }
    }
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); } catch (Exception e) { return Map.of(); }
    }
}
