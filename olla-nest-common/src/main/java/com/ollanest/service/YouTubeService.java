package com.ollanest.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube transcript extraction — fetches video transcripts for RAG ingestion and chat context.
 *
 * Extracts transcripts from YouTube's timedtext API endpoint. Transcripts are
 * cached in the youtube_transcripts table for 30 days.
 */
@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:v=|youtu\\.be/|embed/|v/|shorts/)([a-zA-Z0-9_-]{11})");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    private final JdbcTemplate db;

    public YouTubeService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Extracts the plain-text transcript for a YouTube video, using a 30-day DB cache.
     *
     * @param urlOrId a full YouTube URL or bare 11-character video ID
     * @return the transcript as a single space-separated string, or {@code null} if unavailable
     */
    public String getTranscript(String urlOrId) {
        String videoId = extractVideoId(urlOrId);
        if (videoId == null) return null;

        // Check cache
        var cached = db.queryForList("SELECT transcript FROM youtube_transcripts WHERE video_id=?", videoId);
        if (!cached.isEmpty()) {
            return (String) cached.get(0).get("transcript");
        }

        // Fetch transcript
        String transcript = fetchTranscript(videoId);
        if (transcript != null) {
            String title = fetchTitle(videoId);
            try {
                db.update("INSERT OR REPLACE INTO youtube_transcripts (video_id, title, transcript, cached_at) VALUES (?,?,?,?)",
                        videoId, title, transcript, Instant.now().toString());
            } catch (Exception ignore) {}
        }
        return transcript;
    }

    private String fetchTranscript(String videoId) {
        try {
            // First: get the page to find available caption tracks
            String pageUrl = "https://www.youtube.com/watch?v=" + videoId;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pageUrl))
                    .header("User-Agent", "Mozilla/5.0 (compatible; OllaNest/1.0)")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            // Extract captionTracks from the page's ytInitialPlayerResponse
            Pattern captionPat = Pattern.compile("\"captionTracks\":\\[\\{\"baseUrl\":\"([^\"]+)\"");
            Matcher m = captionPat.matcher(html);
            if (!m.find()) return null;

            String captionUrl = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            captionUrl = captionUrl.replace("\\u0026", "&");

            // Fetch the caption XML
            HttpRequest capReq = HttpRequest.newBuilder()
                    .uri(URI.create(captionUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> capResp = HTTP.send(capReq, HttpResponse.BodyHandlers.ofString());

            // Parse XML transcript
            Document doc = Jsoup.parse(capResp.body(), "", org.jsoup.parser.Parser.xmlParser());
            StringBuilder transcript = new StringBuilder();
            for (Element text : doc.select("text")) {
                String content = text.text().replaceAll("&#39;", "'").replaceAll("&amp;", "&").trim();
                if (!content.isBlank()) {
                    transcript.append(content).append(" ");
                }
            }
            return transcript.toString().trim();

        } catch (Exception e) {
            log.debug("[youtube] Transcript fetch failed for {}: {}", videoId, e.getMessage());
            return null;
        }
    }

    private String fetchTitle(String videoId) {
        try {
            String url = "https://www.youtube.com/watch?v=" + videoId;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0").timeout(Duration.ofSeconds(10)).GET().build();
            String html = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Document doc = Jsoup.parse(html);
            return doc.title().replace(" - YouTube", "").trim();
        } catch (Exception e) {
            return "YouTube Video " + videoId;
        }
    }

    /**
     * Extracts the 11-character YouTube video ID from a URL or returns the input if it is
     * already a bare video ID.
     *
     * @param input a YouTube URL (watch, youtu.be, embed, shorts) or a bare video ID
     * @return the 11-character video ID, or {@code null} if extraction fails
     */
    public String extractVideoId(String input) {
        if (input == null || input.isBlank()) return null;
        // Direct 11-char video ID
        if (input.matches("[a-zA-Z0-9_-]{11}")) return input;
        Matcher m = VIDEO_ID_PATTERN.matcher(input);
        return m.find() ? m.group(1) : null;
    }
}
