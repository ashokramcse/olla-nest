package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link SearchCacheService}.
 *
 * <p>Covers: {@code cacheKey()} — determinism and distinctness; {@code get()} — null when
 * file missing, null when DB has no record, null when expired; {@code put()} — file written,
 * DB INSERT called; {@code cleanExpired()} — expired entries deleted.
 *
 * <p>Uses {@link TempDir} + {@code ReflectionTestUtils} to inject {@code dataDir}.
 * All DB interactions are Mockito-stubbed.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SearchCacheService — unit tests")
class SearchCacheServiceTest {

    @TempDir
    Path tempDir;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks SearchCacheService svc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "dataDir", tempDir.toString());
        // Stub cache-file count check to return 0 (not over capacity)
        when(db.queryForObject(contains("COUNT"), eq(Integer.class))).thenReturn(0);
    }

    // ── cacheKey() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cacheKey()")
    class CacheKey {

        @Test
        @DisplayName("same query + provider always returns the same key (determinism)")
        void deterministicKey() {
            // Two calls with identical arguments must produce identical keys for cache lookup to work
            String k1 = svc.cacheKey("AI trends 2026", "duckduckgo");
            String k2 = svc.cacheKey("AI trends 2026", "duckduckgo");
            assertThat(k1).isEqualTo(k2);
        }

        @Test
        @DisplayName("different queries produce different keys")
        void differentQueriesDifferentKeys() {
            // Different queries must map to different cache slots — key collisions would return wrong results
            String k1 = svc.cacheKey("query A", "duckduckgo");
            String k2 = svc.cacheKey("query B", "duckduckgo");
            assertThat(k1).isNotEqualTo(k2);
        }

        @Test
        @DisplayName("same query with different providers produces different keys")
        void differentProvidersDifferentKeys() {
            // Provider is part of the cache key — DuckDuckGo and Brave results must not collide
            String k1 = svc.cacheKey("climate change", "duckduckgo");
            String k2 = svc.cacheKey("climate change", "brave");
            assertThat(k1).isNotEqualTo(k2);
        }

        @Test
        @DisplayName("key is a 64-char hex string (SHA-256)")
        void keyIs64CharHex() {
            // SHA-256 output is always 64 lowercase hex characters — verify the format
            String key = svc.cacheKey("some query", "provider");
            assertThat(key).matches("[0-9a-f]{64}");
        }
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("returns null when cache file does not exist")
        void returnsNullWhenFileMissing() {
            // No file on disk — must return null (cache miss), not throw
            assertThat(svc.get("nonexistent-key")).isNull();
        }

        @Test
        @DisplayName("returns null when DB has no record for key")
        void returnsNullWhenNoDbRecord() throws Exception {
            String key = svc.cacheKey("test query", "provider");
            // Write cache file but stub DB to return empty — both must be present for a hit
            var file = tempDir.resolve("search_cache").resolve(key + ".cache");
            file.getParent().toFile().mkdirs();
            java.nio.file.Files.writeString(file, "[]");
            when(db.queryForList(anyString(), eq(key))).thenReturn(List.of());
            // DB record missing even though file exists — must return null
            assertThat(svc.get(key)).isNull();
        }

        @Test
        @DisplayName("returns null when cache entry is expired")
        void returnsNullWhenExpired() throws Exception {
            String key = svc.cacheKey("expired query", "prov");
            var file = tempDir.resolve("search_cache").resolve(key + ".cache");
            file.getParent().toFile().mkdirs();
            java.nio.file.Files.writeString(file, "[]");
            // Stub DB with past expires_at — 1 hour ago
            String pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS).toString();
            when(db.queryForList(anyString(), eq(key)))
                    .thenReturn(List.of(Map.of("expires_at", pastExpiry)));
            // Expired entries must not be served — must return null to force a fresh search
            assertThat(svc.get(key)).isNull();
        }
    }

    // ── put() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("put()")
    class Put {

        @Test
        @DisplayName("writes cache file to disk")
        void writesCacheFile() throws Exception {
            // Stub mapper to return empty JSON array for the cache file contents
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            String key = svc.cacheKey("put test", "duckduckgo");
            svc.put(key, "put test", "duckduckgo", "general", List.of());
            // Cache file must exist on disk after put() — missing file = broken cache
            var cacheFile = tempDir.resolve("search_cache").resolve(key + ".cache");
            assertThat(java.nio.file.Files.exists(cacheFile)).isTrue();
        }

        @Test
        @DisplayName("inserts DB record with correct cache_key")
        void insertsDbRecord() throws Exception {
            // Stub mapper for file write
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            String key = svc.cacheKey("db record test", "brave");
            svc.put(key, "db record test", "brave", "general", List.of());
            // DB index record must be created so get() can find the cache entry
            verify(db).update(contains("INSERT OR REPLACE INTO search_cache_index"), eq(key), any(), any(), any(), any(), any());
        }
    }

    // ── cleanExpired() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanExpired()")
    class CleanExpired {

        @Test
        @DisplayName("queries for expired entries and calls evict for each")
        void deletesExpiredEntries() {
            // Stub: two expired cache entries found by the sweep query
            when(db.queryForList(contains("WHERE expires_at <"), any()))
                    .thenReturn(List.of(Map.of("cache_key", "key-1"), Map.of("cache_key", "key-2")));
            svc.cleanExpired();
            // Each expired entry must trigger a DELETE — both key-1 and key-2 must be evicted
            verify(db, atLeast(2)).update(contains("DELETE FROM search_cache_index WHERE cache_key"), any());
        }

        @Test
        @DisplayName("no exception when no expired entries")
        void noExceptionWhenNoneExpired() {
            // Stub: sweep finds no expired entries — must be a no-op, not an error
            when(db.queryForList(contains("WHERE expires_at <"), any())).thenReturn(List.of());
            // No exception = cleanExpired is safe to call even when the cache is fully current
            assertThatCode(() -> svc.cleanExpired()).doesNotThrowAnyException();
        }
    }
}
