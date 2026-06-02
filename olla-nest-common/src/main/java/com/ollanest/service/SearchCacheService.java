package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Disk-based LRU search cache for web search results.
 *
 * Cache entries are stored as JSON files under data/search_cache/ with SHA-256 keys.
 * TTL varies by query type: news=15min, general=1h, research=6h.
 * LRU eviction enforced at 1000 entries. Index tracked in the search_cache_index table.
 */
@Service
public class SearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheService.class);
    private static final int MAX_ENTRIES = 1000;

    @Value("${app.data-dir:./data}")
    private String dataDir;

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public SearchCacheService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public String cacheKey(String query, String provider) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((query + "|" + provider).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) { return UUID.randomUUID().toString(); }
    }

    public List<WebSearchService.SearchResult> get(String cacheKey) {
        try {
            Path file = cacheFile(cacheKey);
            if (!Files.exists(file)) return null;

            // Check expiry in DB
            var rows = db.queryForList("SELECT expires_at FROM search_cache_index WHERE cache_key=?", cacheKey);
            if (rows.isEmpty()) return null;
            String expiresAt = (String) rows.get(0).get("expires_at");
            if (Instant.parse(expiresAt).isBefore(Instant.now())) {
                evict(cacheKey);
                return null;
            }

            // Update hit count
            db.update("UPDATE search_cache_index SET hit_count=hit_count+1 WHERE cache_key=?", cacheKey);

            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<Map<String, Object>> raw = mapper.readValue(json, new TypeReference<>() {});
            return raw.stream().map(r -> new WebSearchService.SearchResult(
                    (String) r.get("title"), (String) r.get("url"), (String) r.getOrDefault("snippet", "")
            )).toList();
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String cacheKey, String query, String provider, String queryType,
            List<WebSearchService.SearchResult> results) {
        try {
            // Enforce LRU cap
            int count = db.queryForObject("SELECT COUNT(*) FROM search_cache_index", Integer.class);
            if (count >= MAX_ENTRIES) evictOldest(MAX_ENTRIES / 10);

            Path file = cacheFile(cacheKey);
            Files.createDirectories(file.getParent());

            List<Map<String, String>> data = results.stream().map(r ->
                    Map.of("title", r.title(), "url", r.url(), "snippet", r.snippet())
            ).toList();
            Files.writeString(file, mapper.writeValueAsString(data), StandardCharsets.UTF_8);

            String expiresAt = Instant.now().plus(ttlMinutes(queryType), ChronoUnit.MINUTES).toString();
            db.update("""
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
        } catch (Exception ignore) {}
    }

    private void evictOldest(int count) {
        var oldest = db.queryForList(
                "SELECT cache_key FROM search_cache_index ORDER BY hit_count ASC, cached_at ASC LIMIT ?", count);
        for (var row : oldest) evict((String) row.get("cache_key"));
    }

    private Path cacheFile(String key) {
        return Path.of(dataDir, "search_cache", key + ".cache");
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 600000)
    public void cleanExpired() {
        try {
            var expired = db.queryForList(
                    "SELECT cache_key FROM search_cache_index WHERE expires_at < ?", Instant.now().toString());
            for (var row : expired) evict((String) row.get("cache_key"));
        } catch (Exception e) {
            log.debug("[search-cache] Cleanup error: {}", e.getMessage());
        }
    }
}
