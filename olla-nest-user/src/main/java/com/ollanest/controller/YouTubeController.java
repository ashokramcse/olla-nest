package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.YouTubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller that extracts YouTube video transcripts for RAG ingestion and
 * chat context.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users can feed a YouTube URL to the assistant and have its transcript used as
 * context (for summarisation, Q&amp;A, or knowledge-base ingestion). This
 * controller validates the URL, resolves the video id, and returns the
 * transcript text, delegating the actual fetch to {@link YouTubeService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Requires authentication but no per-user ownership, since transcripts are
 * public content.</li>
 * <li>Distinguishes a malformed URL (400) from a video that simply has no
 * available transcript (404).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/youtube")
public class YouTubeController extends BaseController {

    /** Service that resolves video ids and fetches transcripts. */
    private final YouTubeService youtubeService;

    /**
     * Constructor-injects the YouTube service.
     *
     * @param youtubeService the service backing transcript extraction
     * @since v2026.2.1
     */
    public YouTubeController(YouTubeService youtubeService) {
        this.youtubeService = youtubeService;
    }

    /**
     * Fetches the transcript for a YouTube video.
     *
     * @param req the HTTP request; authentication is required
     * @param url the YouTube video URL or id
     * @return an OK response with the video id, transcript text, and character
     *         count; a 400 if the URL is invalid; or a 404 if no transcript is
     *         available
     * @since v2026.2.1
     */
    @GetMapping("/transcript")
    public ResponseEntity<?> transcript(HttpServletRequest req,
            @RequestParam String url) {
        requireAuth(req);
        String videoId = youtubeService.extractVideoId(url);
        if (videoId == null) return badRequest("Invalid YouTube URL or video ID");
        String transcript = youtubeService.getTranscript(url);
        if (transcript == null) return notFound("Transcript not available for this video");
        return ok(Map.of("video_id", videoId, "transcript", transcript, "chars", transcript.length()));
    }
}
