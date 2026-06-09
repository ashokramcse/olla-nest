package com.ollanest.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Disk-backed LRU cache for web search results, keyed by a SHA-256 hash of the
 * query and provider.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Repeated web searches for the same query waste API quota and slow down the
 * agent loop. This service caches search results as JSON files on disk with
 * per-query-type TTLs (15 min for news, 1 h for general queries, 6 h for deep
 * research) so subsequent identical queries are served immediately from disk
 * without hitting the search API.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The cache index ({@code search_cache_index}) is stored in SQLite for
 * atomic TTL checks and hit-count tracking; the actual result payloads are
 * stored as flat JSON files to avoid storing large blobs in the database.</li>
 * <li>LRU eviction removes the least-recently-hit, oldest entries when the cap
 * of 1 000 entries is reached, evicting 10% at a time.</li>
 * <li>A scheduled cleanup job ({@link #cleanExpired}) runs hourly to remove
 * expired entries proactively, so disk space is reclaimed even for queries that
 * are never requested again.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the search and research expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class SearchCacheService {

	private static final Logger log = LoggerFactory.getLogger(SearchCacheService.class);

	/** Maximum number of cache entries before LRU eviction is triggered. */
	private static final int MAX_ENTRIES = 1000;

	/**
	 * Root data directory; cache files are stored under
	 * {@code {dataDir}/search_cache/}.
	 */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/**
	 * JDBC template for reading and updating the {@code search_cache_index} table.
	 */
	private final JdbcTemplate db;

	/**
	 * Shared Jackson mapper for serializing and deserializing cached result lists.
	 */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialization dependencies.
	 *
	 * @param db     the JDBC template for cache index operations
	 * @param mapper the shared Jackson object mapper
	 * @since v2026.2.1
	 */
	public SearchCacheService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/**
	 * Computes a deterministic SHA-256 cache key for a query and provider
	 * combination.
	 *
	 * @param query    the search query string
	 * @param provider the search provider identifier (e.g. {@code "brave"},
	 *                 {@code "serper"})
	 * @return a hex-encoded SHA-256 hash, or a random UUID string on hash failure
	 * @since v2026.2.1
	 */
	public String cacheKey(String query, String provider) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest((query + "|" + provider).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (Exception e) {
			return UUID.randomUUID().toString();
		}
	}

	/**
	 * Returns cached search results for the given cache key, or {@code null} on
	 * miss or expiry.
	 *
	 * @param cacheKey the key produced by {@link #cacheKey}
	 * @return cached results, or {@code null} if not cached or expired
	 * @since v2026.2.1
	 */
	public List<WebSearchService.SearchResult> get(String cacheKey) {
		try {
			Path file = cacheFile(cacheKey);
			if (!Files.exists(file))
				return null;

			// Check expiry in DB
			var rows = db.queryForList("SELECT expires_at FROM search_cache_index WHERE cache_key=?", cacheKey);
			if (rows.isEmpty())
				return null;
			String expiresAt = (String) rows.get(0).get("expires_at");
			if (Instant.parse(expiresAt).isBefore(Instant.now())) {
				evict(cacheKey);
				return null;
			}

			// Update hit count
			db.update("UPDATE search_cache_index SET hit_count=hit_count+1 WHERE cache_key=?", cacheKey);

			String json = Files.readString(file, StandardCharsets.UTF_8);
			List<Map<String, Object>> raw = mapper.readValue(json, new TypeReference<>() {
			});
			return raw.stream().map(r -> new WebSearchService.SearchResult((String) r.get("title"),
					(String) r.get("url"), (String) r.getOrDefault("snippet", ""))).toList();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Stores search results in the cache, enforcing the LRU cap if necessary.
	 *
	 * @param cacheKey  the key produced by {@link #cacheKey}
	 * @param query     the original query string (stored in the index for
	 *                  debugging)
	 * @param provider  the search provider identifier
	 * @param queryType the query type ({@code "news"}, {@code "research"}, or
	 *                  {@code "general"}) used to select the TTL
	 * @param results   the search results to cache
	 * @since v2026.2.1
	 */
	public void put(String cacheKey, String query, String provider, String queryType,
			List<WebSearchService.SearchResult> results) {
		try {
			// Enforce LRU cap
			int count = db.queryForObject("SELECT COUNT(*) FROM search_cache_index", Integer.class);
			if (count >= MAX_ENTRIES)
				evictOldest(MAX_ENTRIES / 10);

			Path file = cacheFile(cacheKey);
			Files.createDirectories(file.getParent());

			List<Map<String, String>> data = results.stream()
					.map(r -> Map.of("title", r.title(), "url", r.url(), "snippet", r.snippet())).toList();
			Files.writeString(file, mapper.writeValueAsString(data), StandardCharsets.UTF_8);

			String expiresAt = Instant.now().plus(ttlMinutes(queryType), ChronoUnit.MINUTES).toString();
			db.update(
					"""
							INSERT OR REPLACE INTO search_cache_index (cache_key, query, provider, query_type, cached_at, expires_at, hit_count)
							VALUES (?,?,?,?,?,?,0)""",
					cacheKey, query, provider, queryType, Instant.now().toString(), expiresAt);
		} catch (Exception e) {
			log.debug("[search-cache] Put failed: {}", e.getMessage());
		}
	}

	private long ttlMinutes(String queryType) {
		return switch (queryType != null ? queryType : "general") {
		case "news" -> 15;
		case "research" -> 360;
		default -> 60;
		};
	}

	private void evict(String cacheKey) {
		try {
			Files.deleteIfExists(cacheFile(cacheKey));
			db.update("DELETE FROM search_cache_index WHERE cache_key=?", cacheKey);
		} catch (Exception ignore) {
		}
	}

	private void evictOldest(int count) {
		var oldest = db.queryForList(
				"SELECT cache_key FROM search_cache_index ORDER BY hit_count ASC, cached_at ASC LIMIT ?", count);
		for (var row : oldest)
			evict((String) row.get("cache_key"));
	}

	private Path cacheFile(String key) {
		return Path.of(dataDir, "search_cache", key + ".cache");
	}

	/**
	 * Scheduled cleanup job that removes all expired cache entries. Runs hourly
	 * with a 10-minute initial delay to avoid startup contention.
	 *
	 * @since v2026.2.1
	 */
	@Scheduled(fixedDelay = 3600000, initialDelay = 600000)
	public void cleanExpired() {
		try {
			var expired = db.queryForList("SELECT cache_key FROM search_cache_index WHERE expires_at < ?",
					Instant.now().toString());
			for (var row : expired)
				evict((String) row.get("cache_key"));
		} catch (Exception e) {
			log.debug("[search-cache] Cleanup error: {}", e.getMessage());
		}
	}
}
