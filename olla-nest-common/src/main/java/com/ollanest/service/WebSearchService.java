package com.ollanest.service;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web search abstraction supporting three interchangeable provider backends.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest chat sessions and deep-research pipelines can optionally augment
 * LLM responses with live web results. Rather than hard-coding a single search
 * API, {@code WebSearchService} provides a unified {@link #search(String, int)}
 * entry point that dispatches to whichever provider the admin has configured.
 * This keeps provider credentials and endpoint URLs out of the chat and
 * research layers entirely.
 *
 * <h3>Supported providers</h3>
 * <ul>
 * <li><b>Serper</b> (default) — Google Search results via
 * <a href="https://serper.dev">serper.dev</a>; requires
 * {@code searchApiKey}.</li>
 * <li><b>Brave</b> — Brave Search API at
 * <a href="https://api.search.brave.com">api.search.brave.com</a>; requires
 * {@code searchApiKey}.</li>
 * <li><b>SearXNG</b> — self-hosted meta-search engine; requires
 * {@code searchBaseUrl}; no API key needed.</li>
 * </ul>
 *
 * <p>
 * Provider selection is driven by the {@code searchProvider} setting in the
 * {@code settings} table. If the required key or URL is not configured, the
 * method returns an empty list rather than throwing — so the chat pipeline
 * degrades gracefully.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A single {@link HttpClient} instance is shared across calls (connection
 * pooling).</li>
 * <li>All provider calls have a 15-second response timeout; uncaught exceptions
 * are logged as warnings and result in an empty list, not a 500 error.</li>
 * <li>Result count is capped by the {@code maxResults} parameter; no more than
 * that many {@link SearchResult} entries are ever returned.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; Serper, Brave, and SearXNG backends;
 * {@link #formatResultsForPrompt(List)} helper for system-prompt
 * injection.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see com.ollanest.service.DeepResearchService
 * @see com.ollanest.controller.ChatController
 */
@Service
public class WebSearchService {

	/** SLF4J logger for provider diagnostics and soft-failure warnings. */
	private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

	/** Default number of results to return when the caller does not specify. */
	private static final int DEFAULT_RESULTS = 5;

	/**
	 * Settings service used to read {@code searchProvider}, {@code searchApiKey},
	 * and {@code searchBaseUrl}.
	 */
	private final DatabaseService dbService;

	/** Shared Jackson mapper for deserialising provider API responses. */
	private final ObjectMapper mapper;

	/**
	 * Shared HTTP client with a 10-second connection timeout and automatic redirect
	 * following. Reused across all search calls for connection pooling efficiency.
	 */
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();

	/**
	 * Constructor-injects the database settings service and JSON mapper.
	 *
	 * @param dbService the database service used to read search provider settings
	 * @param mapper    the shared Jackson {@link ObjectMapper}
	 * @since v2026.1.4
	 */
	public WebSearchService(DatabaseService dbService, ObjectMapper mapper) {
		this.dbService = dbService;
		this.mapper = mapper;
	}

	// -------------------------------------------------------------------------
	// Public record type
	// -------------------------------------------------------------------------

	/**
	 * Immutable value type representing a single web search result.
	 *
	 * @param title   the page or article title from the search engine
	 * @param url     the canonical URL of the result
	 * @param snippet a short text excerpt from the page; may be empty but never
	 *                {@code null}
	 * @since v2026.1.4
	 */
	public record SearchResult(String title, String url, String snippet) {
	}

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Executes a web search using the admin-configured provider and returns a list
	 * of results ranked by the provider.
	 *
	 * <p>
	 * Provider dispatch order:
	 * <ol>
	 * <li>Reads {@code searchProvider} from settings (default:
	 * {@code "serper"}).</li>
	 * <li>Dispatches to the corresponding private method.</li>
	 * <li>If the required API key / base URL is absent, logs a debug message and
	 * returns an empty list (no exception propagated).</li>
	 * <li>If the provider call throws, logs a warning and returns an empty
	 * list.</li>
	 * </ol>
	 *
	 * @param query      the search query string; returns empty list if null or
	 *                   blank
	 * @param maxResults maximum number of results to return; values ≤ 0 use
	 *                   {@code DEFAULT_RESULTS} (5)
	 * @return a list of up to {@code maxResults} {@link SearchResult} records,
	 *         ordered by relevance as returned by the provider; never {@code null},
	 *         may be empty
	 * @since v2026.1.4
	 */
	public List<SearchResult> search(String query, int maxResults) {
		if (query == null || query.isBlank())
			return List.of();
		String provider = dbService.getSetting("searchProvider", "serper");
		int limit = maxResults > 0 ? maxResults : DEFAULT_RESULTS;
		try {
			return switch (provider) {
			case "brave" -> searchBrave(query, limit);
			case "searxng" -> searchSearXng(query, limit);
			default -> searchSerper(query, limit); // serper is the default
			};
		} catch (Exception e) {
			log.warn("[websearch] provider '{}' failed for query '{}': {}", provider, query, e.getMessage());
			return List.of();
		}
	}

	/**
	 * Formats a list of search results into a plain-text context block suitable for
	 * injection into an LLM system prompt.
	 *
	 * <p>
	 * Output format:
	 * 
	 * <pre>
	 * CURRENT WEB SEARCH RESULTS:
	 * [1] Title
	 * URL: https://...
	 * Snippet text...
	 *
	 * [2] ...
	 * </pre>
	 *
	 * @param results the list of search results to format; must not be {@code null}
	 * @return a formatted string block, or {@code ""} if the list is empty
	 * @since v2026.1.4
	 */
	public String formatResultsForPrompt(List<SearchResult> results) {
		if (results.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("CURRENT WEB SEARCH RESULTS:\n");
		for (int i = 0; i < results.size(); i++) {
			SearchResult r = results.get(i);
			sb.append("[").append(i + 1).append("] ").append(r.title()).append("\n");
			sb.append("URL: ").append(r.url()).append("\n");
			sb.append(r.snippet()).append("\n\n");
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// Provider: Serper (Google Search results)
	// -------------------------------------------------------------------------

	/**
	 * Searches via the Serper API (Google Search results).
	 *
	 * <p>
	 * Sends a JSON POST to {@code https://google.serper.dev/search} with the query
	 * and result count. Parses the {@code organic} array from the response. Returns
	 * an empty list if {@code searchApiKey} is not configured.
	 *
	 * @param query the search query
	 * @param limit maximum number of results to return
	 * @return list of search results from the {@code organic} array
	 * @throws Exception on network error or JSON parse failure
	 * @since v2026.1.4
	 */
	private List<SearchResult> searchSerper(String query, int limit) throws Exception {
		String apiKey = dbService.getSetting("searchApiKey", "");
		if (apiKey.isBlank()) {
			log.debug("[websearch] Serper API key not configured");
			return List.of();
		}
		String json = mapper.writeValueAsString(Map.of("q", query, "num", limit));
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://google.serper.dev/search"))
				.header("X-API-KEY", apiKey).header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
		List<SearchResult> results = new ArrayList<>();
		for (JsonNode r : root.path("organic")) {
			results.add(
					new SearchResult(r.path("title").asText(), r.path("link").asText(), r.path("snippet").asText("")));
			if (results.size() >= limit)
				break;
		}
		return results;
	}

	// -------------------------------------------------------------------------
	// Provider: Brave Search API
	// -------------------------------------------------------------------------

	/**
	 * Searches via the Brave Search API.
	 *
	 * <p>
	 * Sends a GET to {@code https://api.search.brave.com/res/v1/web/search} with
	 * the query URL-encoded and count parameter. Parses the {@code web.results}
	 * array from the response. Returns an empty list if {@code searchApiKey} is not
	 * configured.
	 *
	 * @param query the search query
	 * @param limit maximum number of results to return
	 * @return list of search results from the {@code web.results} array
	 * @throws Exception on network error or JSON parse failure
	 * @since v2026.1.4
	 */
	private List<SearchResult> searchBrave(String query, int limit) throws Exception {
		String apiKey = dbService.getSetting("searchApiKey", "");
		if (apiKey.isBlank()) {
			log.debug("[websearch] Brave API key not configured");
			return List.of();
		}
		String url = "https://api.search.brave.com/res/v1/web/search?q="
				+ URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=" + limit;
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json")
				.header("X-Subscription-Token", apiKey).timeout(Duration.ofSeconds(15)).GET().build();
		JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
		List<SearchResult> results = new ArrayList<>();
		for (JsonNode r : root.path("web").path("results")) {
			results.add(new SearchResult(r.path("title").asText(), r.path("url").asText(),
					r.path("description").asText("")));
			if (results.size() >= limit)
				break;
		}
		return results;
	}

	// -------------------------------------------------------------------------
	// Provider: SearXNG (self-hosted, no API key)
	// -------------------------------------------------------------------------

	/**
	 * Searches via a self-hosted SearXNG instance.
	 *
	 * <p>
	 * Sends a GET to {@code {searchBaseUrl}/search?q=...&format=json}. No API key
	 * is required — SearXNG is an open meta-search engine typically deployed
	 * internally. Returns an empty list if {@code searchBaseUrl} is not configured.
	 *
	 * @param query the search query
	 * @param limit maximum number of results to return
	 * @return list of search results from the {@code results} array
	 * @throws Exception on network error or JSON parse failure
	 * @since v2026.1.4
	 */
	private List<SearchResult> searchSearXng(String query, int limit) throws Exception {
		String baseUrl = dbService.getSetting("searchBaseUrl", "");
		if (baseUrl.isBlank()) {
			log.debug("[websearch] SearXNG base URL not configured");
			return List.of();
		}
		String url = baseUrl.replaceAll("/$", "") + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
				+ "&format=json&categories=general&language=en";
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15)).GET().build();
		JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
		List<SearchResult> results = new ArrayList<>();
		for (JsonNode r : root.path("results")) {
			results.add(
					new SearchResult(r.path("title").asText(), r.path("url").asText(), r.path("content").asText("")));
			if (results.size() >= limit)
				break;
		}
		return results;
	}
}
