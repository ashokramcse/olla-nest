package com.ollanest.service;

import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * OCD-level unit tests for {@link YouTubeService}.
 *
 * <p>Covers: {@code getTranscript()} — returns cached transcript when DB hit;
 * returns null for unextractable URLs; returns null for null/blank input;
 * all common YouTube URL patterns extract the correct video ID (tested via DB cache hit path).
 *
 * <p>The private {@code extractVideoId()} is tested indirectly via {@code getTranscript()}.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("YouTubeService — unit tests")
class YouTubeServiceTest {

    @Mock JdbcTemplate db;

    @InjectMocks YouTubeService svc;

    /** Stub DB to return a cached transcript for a given video ID. */
    private void stubCachedTranscript(String videoId, String transcript) {
        when(db.queryForList(contains("FROM youtube_transcripts WHERE video_id"), eq(videoId)))
                .thenReturn(List.of(Map.of("transcript", transcript)));
    }

    // ── getTranscript() — cache hit ───────────────────────────────────────────

    @Nested
    @DisplayName("getTranscript() — cache hit")
    class CacheHit {

        @Test
        @DisplayName("returns cached transcript when DB has a record")
        void returnsCachedTranscript() {
            // Stub: DB already has a cached transcript for this video ID
            stubCachedTranscript("dQw4w9WgXcQ", "Never gonna give you up...");
            // Cache hit — no external API call needed
            String result = svc.getTranscript("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            assertThat(result).isEqualTo("Never gonna give you up...");
        }

        @Test
        @DisplayName("standard watch URL extracts 11-char video ID correctly")
        void standardWatchUrl() {
            // Most common YouTube URL format: ?v=<11-char-ID>
            stubCachedTranscript("dQw4w9WgXcQ", "cached");
            assertThat(svc.getTranscript("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("cached");
        }

        @Test
        @DisplayName("youtu.be short URL extracts video ID correctly")
        void youtuBeShortUrl() {
            // Short-link format: youtu.be/<ID>
            stubCachedTranscript("dQw4w9WgXcQ", "short-url-transcript");
            assertThat(svc.getTranscript("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("short-url-transcript");
        }

        @Test
        @DisplayName("shorts URL extracts video ID correctly")
        void shortsUrl() {
            // YouTube Shorts format: /shorts/<ID>
            stubCachedTranscript("dQw4w9WgXcQ", "shorts-transcript");
            assertThat(svc.getTranscript("https://www.youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("shorts-transcript");
        }

        @Test
        @DisplayName("direct 11-char video ID returns cached transcript")
        void directVideoId() {
            // User pastes just the raw 11-character ID (no URL prefix)
            stubCachedTranscript("dQw4w9WgXcQ", "direct-id-transcript");
            assertThat(svc.getTranscript("dQw4w9WgXcQ")).isEqualTo("direct-id-transcript");
        }
    }

    // ── getTranscript() — null / invalid inputs ───────────────────────────────

    @Nested
    @DisplayName("getTranscript() — null / invalid inputs")
    class NullAndInvalid {

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() {
            // Null guard — agent may pass null when no URL was provided
            assertThat(svc.getTranscript(null)).isNull();
        }

        @Test
        @DisplayName("returns null for blank input")
        void returnsNullForBlank() {
            // Blank guard — whitespace-only input has no video ID to extract
            assertThat(svc.getTranscript("   ")).isNull();
        }

        @Test
        @DisplayName("returns null when video ID cannot be extracted from URL")
        void returnsNullForUnextractableUrl() {
            // Non-YouTube domain: no video ID pattern matches — graceful null
            assertThat(svc.getTranscript("https://vimeo.com/123456789")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com",
            "not-a-url",
            "ftp://youtube.com",
            "dQw4w9WgXc" // 10 chars — too short for direct ID
        })
        @DisplayName("returns null for inputs that have no valid YouTube video ID")
        void returnsNullForVariousInvalidInputs(String input) {
            // No cache stub → extractVideoId returns null → getTranscript returns null
            // (tests the private extractVideoId indirectly via public getTranscript)
            assertThat(svc.getTranscript(input)).isNull();
        }
    }
}
