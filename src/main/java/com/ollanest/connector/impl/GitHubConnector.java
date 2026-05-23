package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GitHub connector — syncs repos, issues, PRs, and README files.
 * Credentials JSON: { "token": "ghp_..." }
 * Config JSON:      { "owner": "myorg", "repo": "myrepo" }  (repo optional; syncs all if absent)
 */
@Component
public class GitHubConnector extends BaseConnector {

    @Override public String getType() { return "github"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token = credStr(creds, "token");
        String auth  = "Bearer " + token;
        Map<String, Object> cfg = parseConfig(config);
        String owner = credStr(cfg, "owner");
        String repo  = credStr(cfg, "repo");
        String connId = (String) config.get("id");

        int synced = 0, skipped = 0;
        try {
            // Determine which repos to sync
            String reposUrl = repo.isBlank()
                    ? "https://api.github.com/orgs/" + owner + "/repos?per_page=50&type=all"
                    : "https://api.github.com/repos/" + owner + "/" + repo;

            JsonNode repoNode = httpGet(reposUrl, auth);
            JsonNode repos = repoNode.isArray() ? repoNode : mapper.createArrayNode().add(repoNode);

            for (JsonNode r : repos) {
                String rName = r.path("full_name").asText();
                String rDesc = r.path("description").asText("");
                String rUrl  = r.path("html_url").asText();

                // README
                try {
                    JsonNode rm = httpGet("https://api.github.com/repos/" + rName + "/readme", auth);
                    String content = new String(java.util.Base64.getMimeDecoder()
                            .decode(rm.path("content").asText("")));
                    if (ingestDocument(connId, rName + "/README", rName + " README", rUrl, content)) synced++;
                    else skipped++;
                } catch (Exception ignore) {}

                // Issues (open)
                JsonNode issues = httpGet("https://api.github.com/repos/" + rName +
                        "/issues?state=open&per_page=50&sort=updated", auth);
                for (JsonNode issue : issues) {
                    if (issue.has("pull_request")) continue; // skip PRs here
                    String title   = issue.path("title").asText();
                    String body    = issue.path("body").asText("");
                    String issueUrl= issue.path("html_url").asText();
                    String extId   = rName + "/issue/" + issue.path("number").asInt();
                    String text    = "# " + title + "\n\n" + body;
                    if (ingestDocument(connId, extId, title, issueUrl, text)) synced++;
                    else skipped++;
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[github] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            httpGet("https://api.github.com/user", "Bearer " + credStr(creds, "token"));
            return true;
        } catch (Exception e) { return false; }
    }

    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception e) { return Map.of(); }
    }
}
