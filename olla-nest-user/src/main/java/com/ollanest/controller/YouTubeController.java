package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.YouTubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** YouTube transcript extraction for RAG ingestion and chat context. */
@RestController
@RequestMapping("/api/youtube")
public class YouTubeController extends BaseController {

    private final YouTubeService youtubeService;

    public YouTubeController(YouTubeService youtubeService) {
        this.youtubeService = youtubeService;
    }

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
