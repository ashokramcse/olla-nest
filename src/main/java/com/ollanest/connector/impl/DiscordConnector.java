package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Spring {@code @Component} that pulls Discord text-channel messages into the Olla-Nest
 * knowledge index.
 *
 * <h3>Why this class exists</h3>
 * Engineering and product teams increasingly use Discord as an async communication layer.
 * Important decisions, support answers, and design discussions that happen in Discord channels
 * are otherwise invisible to the AI search layer. This connector walks every text channel in a
 * configured guild, fetches the last 100 messages per channel, and ingests them as a single
 * bulleted Markdown document so that channel history becomes searchable.
 *
 * <h3>Credential format</h3>
 * <pre>
 * {
 *   "botToken": "Bot XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"  // Discord bot token; "Bot " prefix is
 *                                                       // added automatically if missing
 * }
 * </pre>
 * The bot must be a member of the target guild with the {@code Read Message History} permission
 * in each channel to be synced.
 *
 * <h3>Configuration format</h3>
 * <pre>
 * {
 *   "guildId": "123456789012345678"   // Discord guild (server) snowflake ID
 * }
 * </pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Only channels whose {@code type} equals {@code 0} (GUILD_TEXT) are processed; voice,
 *       announcement, and stage channels are skipped.</li>
 *   <li>If the bot lacks permission to read a specific channel, that channel is logged at
 *       WARN level and skipped; the rest of the sync proceeds normally.</li>
 *   <li>Empty messages (e.g., embed-only posts) are filtered out before ingestion.</li>
 *   <li>Each channel's entire message batch is stored as one document keyed by the channel's
 *       snowflake ID, so re-syncs replace rather than append content.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.4
 * @version v2026.1.4
 * @see     com.ollanest.connector.BaseConnector
 */
@Component
public class DiscordConnector extends BaseConnector {

    /** Root URL for the Discord REST API v10. */
    private static final String BASE = "https://discord.com/api/v10";

    /**
     * Returns the connector-type discriminator used to match this implementation to a persisted
     * connector row whose {@code type} column equals {@code "discord"}.
     *
     * @return the string {@code "discord"}
     * @since v2026.1.4
     */
    @Override
    public String getType() {
        return "discord";
    }

    /**
     * Synchronises the last 100 messages from every text channel in the configured Discord guild
     * with the Olla-Nest document store.
     *
     * <p>The method performs the following steps:
     * <ol>
     *   <li>Lists all channels in the guild via {@code GET /guilds/:guildId/channels}.</li>
     *   <li>Filters to text channels ({@code type == 0}) only.</li>
     *   <li>For each text channel, fetches the last 100 messages.</li>
     *   <li>Concatenates non-empty message content as a bulleted Markdown list and ingests it
     *       as a single document keyed by the channel's snowflake ID.</li>
     * </ol>
     *
     * @param  config      connector row from the database; must contain {@code "id"} (connector
     *                     UUID) and {@code "config_json"} with at least {@code "guildId"}
     * @param  credentials JSON string containing {@code "botToken"}
     * @return {@link SyncResult} with counts of synced and skipped documents, or an error message
     *         if the guild channel listing fails
     * @since v2026.1.4
     */
    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        Map<String, Object> cfg = parseConfig(config);
        String token  = credStr(creds, "botToken");
        String auth   = token.startsWith("Bot ") ? token : "Bot " + token;
        String guildId = credStr(cfg, "guildId");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode channels = httpGet(BASE + "/guilds/" + guildId + "/channels", auth);
            for (JsonNode ch : channels) {
                if (ch.path("type").asInt() != 0) continue; // text channels only
                String chId   = ch.path("id").asText();
                String chName = ch.path("name").asText();
                try {
                    JsonNode messages = httpGet(BASE + "/channels/" + chId + "/messages?limit=100", auth);
                    StringBuilder sb = new StringBuilder("# #" + chName + "\n\n");
                    for (JsonNode m : messages) {
                        String txt = m.path("content").asText("").trim();
                        if (!txt.isEmpty()) sb.append("• ").append(txt).append("\n");
                    }
                    if (ingestDocument(connId, chId, "#" + chName, "https://discord.com/channels/" + guildId + "/" + chId, sb.toString())) synced++;
                    else skipped++;
                } catch (Exception ex) {
                    log.warn("[discord] skip #{}: {}", chName, ex.getMessage());
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[discord] {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    /**
     * Verifies that the supplied bot token can authenticate against the Discord
     * {@code /users/@me} endpoint.
     *
     * @param  config      connector configuration map (not used for this check)
     * @param  credentials JSON string containing {@code "botToken"}
     * @return {@code true} if the HTTP call succeeds without an exception; {@code false} otherwise
     * @since v2026.1.4
     */
    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            String t = credStr(creds, "botToken");
            httpGet(BASE + "/users/@me", t.startsWith("Bot ") ? t : "Bot " + t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses the {@code config_json} field from the connector row into a typed map.
     *
     * <p>Returns an empty map if the field is absent or contains malformed JSON, allowing
     * callers to degrade gracefully rather than throw.
     *
     * @param  config connector row map; must contain {@code "config_json"} as a JSON string
     * @return a {@code Map<String, Object>} representing the parsed configuration, never
     *         {@code null}; empty map on parse failure
     * @since v2026.1.4
     */
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }
}
