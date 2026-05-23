package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search service supporting three providers:
 *  - Serper (Google results) — https://serper.dev
 *  - Brave Search API        — https://api.search.brave.com
 *  - SearXNG (self-hosted)   — any SearXNG instance
 *
 * Provider + API key are stored in the settings table.
 * Falls back gracefully to an empty result list when unconfigured.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final int DEFAULT_RESULTS = 5;

    private final DatabaseService dbService;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public WebSearchService(DatabaseService dbService, ObjectMapper mapper) {
        this.dbService = dbService;
        this.mapper    = mapper;
    }

    public record SearchResult(String title, String url, String snippet) {}

    /** Run a search using the configured provider. Returns empty list if not configured. */
    public List<SearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) return List.of();
        String provider = dbService.getSetting("searchProvider", "serper");
        int limit = maxResults > 0 ? maxResults : DEFAULT_RESULTS;
        try {
            return switch (provider) {
                case "brave"   -> searchBrave(query, limit);
                case "searxng" -> searchSearXng(query, limit);
                default        -> searchSerper(query, limit);   // serper is default
            };
        } catch (Exception e) {
            log.warn("[websearch] provider '{}' failed for query '{}': {}", provider, query, e.getMessage());
            return List.of();
        }
    }

    // ── Serper (Google results) ────────────────────────────────────────────

    private List<SearchResult> searchSerper(String query, int limit) throws Exception {
        String apiKey = dbService.getSetting("searchApiKey", "");
        if (apiKey.isBlank()) { log.debug("[websearch] Serper API key not configured"); return List.of(); }

        String json = mapper.writeValueAsString(Map.of("q", query, "num", limit));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://google.serper.dev/search"))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        List<SearchResult> results = new ArrayList<>();
        for (JsonNode r : root.path("organic")) {
            results.add(new SearchResult(
                    r.path("title").asText(),
                    r.path("link").asText(),
                    r.path("snippet").asText("")));
            if (results.size() >= limit) break;
        }
        return results;
    }

    // ── Brave Search ───────────────────────────────────────────────────────

    private List<SearchResult> searchBrave(String query, int limit) throws Exception {
        String apiKey = dbService.getSetting("searchApiKey", "");
        if (apiKey.isBlank()) { log.debug("[websearch] Brave API key not configured"); return List.of(); }

        String url = "https://api.search.brave.com/res/v1/web/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=" + limit;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .timeout(Duration.ofSeconds(15)).GET().build();
        JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        List<SearchResult> results = new ArrayList<>();
        for (JsonNode r : root.path("web").path("results")) {
            results.add(new SearchResult(
                    r.path("title").asText(),
                    r.path("url").asText(),
                    r.path("description").asText("")));
            if (results.size() >= limit) break;
        }
        return results;
    }

    // ── SearXNG (self-hosted, no API key needed) ───────────────────────────

    private List<SearchResult> searchSearXng(String query, int limit) throws Exception {
        String baseUrl = dbService.getSetting("searchBaseUrl", "");
        if (baseUrl.isBlank()) { log.debug("[websearch] SearXNG base URL not configured"); return List.of(); }
        String url = baseUrl.replaceAll("/$", "") + "/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&categories=general&language=en";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15)).GET().build();
        JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        List<SearchResult> results = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            results.add(new SearchResult(
                    r.path("title").asText(),
                    r.path("url").asText(),
                    r.path("content").asText("")));
            if (results.size() >= limit) break;
        }
        return results;
    }

    /** Format search results as a context block for system prompts. */
    public String formatResultsForPrompt(List<SearchResult> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("CURRENT WEB SEARCH RESULTS:\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ").append(r.title()).append("\n");
            sb.append("URL: ").append(r.url()).append("\n");
            sb.append(r.snippet()).append("\n\n");
        }
        return sb.toString();
    }
}
