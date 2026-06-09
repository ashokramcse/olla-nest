package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Voice I/O service providing speech-to-text transcription and text-to-speech
 * synthesis.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest's chat composer includes a microphone button that lets users
 * dictate messages instead of typing, and a speaker button that reads AI
 * responses aloud. {@code VoiceService} is the single integration point for
 * both capabilities. STT can be handled by a local Ollama Whisper model
 * (default, free, private) or by the OpenAI Whisper API (paid, cloud).
 * TTS always uses the OpenAI TTS-1 API. All HTTP and multipart-form encoding
 * details are contained here so that the {@code VoiceController} remains thin.
 *
 * <h3>Speech-to-Text — provider selection</h3>
 * <p>
 * The {@code sttProvider} setting controls which backend is used:
 * <ul>
 * <li>{@code "local"} (default) — calls a local faster-whisper HTTP server
 * at the URL stored in {@code sttLocalUrl} (default:
 * {@code http://localhost:8765/v1/audio/transcriptions}). Uses the same
 * multipart/form-data format as OpenAI. Free, private, no API key.</li>
 * <li>{@code "openai"} — calls
 * {@code POST https://api.openai.com/v1/audio/transcriptions} using the
 * {@code openaiApiKey} setting. Requires a paid OpenAI account.</li>
 * </ul>
 *
 * <h3>Text-to-Speech (TTS)</h3>
 * <p>
 * Calls {@code POST https://api.openai.com/v1/audio/speech} with a JSON body
 * specifying the model ({@code tts-1}), input text, and voice. Returns raw MP3
 * bytes that the frontend plays directly via the Web Audio API.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The multipart/form-data body for OpenAI Whisper is hand-built because
 * the JDK HTTP client does not include a built-in multipart encoder.</li>
 * <li>Both calls use a 60-second response timeout because audio transcription
 * and synthesis can take several seconds for long inputs.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; OpenAI Whisper STT and TTS-1.</li>
 * <li><b>v2026.1.5</b> — added local faster-whisper STT as default;
 * {@code sttProvider} (local/openai) and {@code sttLocalUrl} settings;
 * removed non-functional Ollama audio route (Ollama does not support
 * audio inference).</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.5
 * @see com.ollanest.controller.VoiceController
 */
@Service
public class VoiceService {

	/** SLF4J logger for voice API diagnostics. */
	private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

	/**
	 * Prefix used in the multipart boundary string to identify Olla Nest requests.
	 */
	private static final String BOUNDARY_PREFIX = "----OllaVoice";

	/** OpenAI Whisper transcription endpoint. */
	private static final String WHISPER_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";

	/** OpenAI TTS synthesis endpoint. */
	private static final String TTS_ENDPOINT = "https://api.openai.com/v1/audio/speech";

	/**
	 * Default local faster-whisper server URL. OpenAI-compatible endpoint.
	 * Start with: {@code pip install faster-whisper uvicorn} then run the server.
	 */
	private static final String DEFAULT_LOCAL_WHISPER_URL = "http://localhost:8765/v1/audio/transcriptions";

	/** Database service used to read the {@code openaiApiKey} setting. */
	private final DatabaseService dbService;

	/**
	 * Shared Jackson mapper for serialising TTS request bodies and parsing Whisper
	 * responses.
	 */
	private final ObjectMapper mapper;

	/**
	 * Shared HTTP client with a 10-second connection timeout. Response timeouts are
	 * set per-request because STT and TTS have variable latency.
	 */
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/**
	 * Constructor-injects the database service and JSON mapper.
	 *
	 * @param dbService used to read {@code openaiApiKey} at call time (not cached,
	 *                  so key rotations take effect immediately)
	 * @param mapper    the shared Jackson {@link ObjectMapper}
	 * @since v2026.1.4
	 */
	public VoiceService(DatabaseService dbService, ObjectMapper mapper) {
		this.dbService = dbService;
		this.mapper = mapper;
	}

	// -------------------------------------------------------------------------
	// Speech-to-Text (Whisper)
	// -------------------------------------------------------------------------

	/**
	 * Transcribes raw audio bytes to text using the OpenAI Whisper API.
	 *
	 * <p>
	 * Constructs a multipart/form-data body containing two parts:
	 * <ol>
	 * <li>{@code model} — always {@code "whisper-1"}</li>
	 * <li>{@code file} — the raw audio bytes with the supplied filename (used by
	 * Whisper for MIME-type detection)</li>
	 * </ol>
	 * The response JSON's {@code text} field contains the transcription.
	 *
	 * <p>
	 * <b>Supported audio formats:</b> WAV, MP3, WebM, OGG, M4A, FLAC.
	 *
	 * @param audioData raw audio bytes from the browser's {@code MediaRecorder};
	 *                  must not be null or empty
	 * @param filename  the original file name including extension (e.g.
	 *                  {@code "audio.webm"}); used for MIME detection — defaults to
	 *                  {@code "audio.wav"} if null
	 * @return the transcribed text string; never null, may be empty if Whisper
	 *         detected no speech
	 * @throws RuntimeException if {@code openaiApiKey} is not configured, or the
	 *                          Whisper API returns an error
	 * @throws Exception        on network failure or JSON parse error
	 * @since v2026.1.4
	 */
	public String transcribe(byte[] audioData, String filename) throws Exception {
		String provider = dbService.getSetting("sttProvider", "local");
		if ("openai".equalsIgnoreCase(provider)) {
			return transcribeWithOpenAI(audioData, filename);
		}
		// Default: local faster-whisper server (OpenAI-compatible)
		return transcribeWithLocalWhisper(audioData, filename);
	}

	/**
	 * Transcribes audio using a local faster-whisper HTTP server.
	 *
	 * <p>
	 * The server must expose an OpenAI-compatible
	 * {@code POST /v1/audio/transcriptions} endpoint. The URL is read from
	 * {@code sttLocalUrl} (default: {@code http://localhost:8765/v1/audio/transcriptions}).
	 *
	 * <p>
	 * Setup (one-time):
	 * <pre>
	 *   pip install faster-whisper
	 *   # then start with the provided server script, or use:
	 *   # https://github.com/nirnaim/faster-whisper-server
	 * </pre>
	 *
	 * @param audioData raw audio bytes
	 * @param filename  original filename including extension
	 * @return transcribed text
	 * @throws RuntimeException if the server returns an error or is unreachable
	 * @throws Exception        on network failure
	 * @since v2026.1.5
	 */
	private String transcribeWithLocalWhisper(byte[] audioData, String filename) throws Exception {
		String url = dbService.getSetting("sttLocalUrl", DEFAULT_LOCAL_WHISPER_URL);
		log.debug("[voice] Local Whisper STT → url={}", url);
		return sendWhisperMultipart(url, null, audioData, filename);
	}

	/**
	 * Transcribes audio using the OpenAI Whisper API (paid, cloud).
	 *
	 * @param audioData raw audio bytes
	 * @param filename  original filename including extension
	 * @return transcribed text
	 * @throws RuntimeException if {@code openaiApiKey} is not configured
	 * @throws Exception        on network failure or JSON parse error
	 * @since v2026.1.4
	 */
	private String transcribeWithOpenAI(byte[] audioData, String filename) throws Exception {
		String apiKey = dbService.getSetting("openaiApiKey", "");
		if (apiKey.isBlank()) {
			throw new RuntimeException("OpenAI API key not configured for transcription");
		}
		return sendWhisperMultipart(WHISPER_ENDPOINT, apiKey, audioData, filename);
	}

	/**
	 * Sends a multipart/form-data audio transcription request to any
	 * OpenAI-compatible Whisper endpoint (local or cloud).
	 *
	 * @param endpoint  the full URL of the transcription endpoint
	 * @param apiKey    Bearer token, or {@code null} for local servers
	 * @param audioData raw audio bytes
	 * @param filename  original filename including extension
	 * @return transcribed text
	 * @throws Exception on network failure or JSON parse error
	 * @since v2026.1.5
	 */
	private String sendWhisperMultipart(String endpoint, String apiKey, byte[] audioData, String filename)
			throws Exception {
		String boundary = BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "");
		String nl = "\r\n";
		ByteArrayOutputStream body = new ByteArrayOutputStream();

		// Part 1: model name (whisper-1 for OpenAI; ignored by faster-whisper server)
		body.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
		body.write(("Content-Disposition: form-data; name=\"model\"" + nl + nl + "whisper-1" + nl)
				.getBytes(StandardCharsets.UTF_8));

		// Part 2: audio file
		String safeName = (filename != null && !filename.isBlank()) ? filename : "audio.webm";
		body.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
		body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"" + nl)
				.getBytes(StandardCharsets.UTF_8));
		body.write(("Content-Type: audio/webm" + nl + nl).getBytes(StandardCharsets.UTF_8));
		body.write(audioData);
		body.write(nl.getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "--" + nl).getBytes(StandardCharsets.UTF_8));

		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(endpoint))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
		if (apiKey != null && !apiKey.isBlank()) {
			reqBuilder.header("Authorization", "Bearer " + apiKey);
		}

		HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			throw new RuntimeException("Whisper server error (HTTP " + resp.statusCode() + "): " + resp.body());
		}
		JsonNode root = mapper.readTree(resp.body());
		if (root.has("error")) {
			throw new RuntimeException("Whisper error: " + root.path("error").path("message").asText());
		}
		return root.path("text").asText();
	}

	// -------------------------------------------------------------------------
	// Text-to-Speech (TTS)
	// -------------------------------------------------------------------------

	/**
	 * Synthesises the given text to speech using the OpenAI TTS-1 API.
	 *
	 * <p>
	 * Sends a JSON POST body with the text, model ({@code tts-1}), and voice to the
	 * OpenAI TTS endpoint. The response body is raw MP3 audio bytes that can be
	 * played directly by the browser via the Web Audio API.
	 *
	 * <p>
	 * Available voices: {@code alloy}, {@code echo}, {@code fable}, {@code onyx},
	 * {@code nova}, {@code shimmer}. If {@code voice} is null or blank, defaults to
	 * {@code "alloy"}.
	 *
	 * @param text  the text to synthesise into speech; must not be null or blank
	 * @param voice the OpenAI TTS voice identifier; defaults to {@code "alloy"} if
	 *              null/blank
	 * @return raw MP3 audio bytes ready for streaming to the browser
	 * @throws RuntimeException if {@code openaiApiKey} is not configured, or the
	 *                          TTS API returns a non-200 status code
	 * @throws Exception        on network failure
	 * @since v2026.1.4
	 */
	public byte[] speak(String text, String voice) throws Exception {
		String apiKey = dbService.getSetting("openaiApiKey", "");
		if (apiKey.isBlank()) {
			throw new RuntimeException("OpenAI API key not configured for TTS");
		}
		String safeVoice = (voice != null && !voice.isBlank()) ? voice : "alloy";
		String body = mapper.writeValueAsString(Map.of("model", "tts-1", "input", text, "voice", safeVoice));
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(TTS_ENDPOINT))
				.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() != 200) {
			throw new RuntimeException("TTS API error: HTTP " + resp.statusCode());
		}
		return resp.body();
	}
}
