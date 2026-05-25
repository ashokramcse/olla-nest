package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Admin REST endpoint for reading application log lines from the rolling log
 * file written by Logback's file appender.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Provides the admin UI with a live log viewer without requiring a separate
 * log-aggregation stack (Loki/Grafana) in development. The endpoint reads the
 * last {@code n} lines of the rolling log file and returns them as a JSON
 * array, optionally filtered by log level or a search term.
 *
 * <h3>Security</h3>
 * <p>
 * All endpoints are admin-only (enforced via {@link #requireAdmin}).
 * The log file path is resolved from a trusted configuration property, not
 * from user input.
 *
 * @author Ashok Ram
 * @since v2026.1.5
 */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogsController extends BaseController {

	/** Resolved from {@code logging.file.name} in application.properties. */
	@Value("${logging.file.name:./data/logs/olla-nest.log}")
	private String logFilePath;

	/** Maximum lines returnable in a single request (hard cap). */
	private static final int MAX_LINES = 2000;

	/**
	 * Returns the last {@code n} lines of the application log file, with optional
	 * level and text filtering.
	 *
	 * @param lines  number of tail lines to return (default 200, max 2000)
	 * @param level  optional level filter: ERROR, WARN, INFO, DEBUG (case-insensitive)
	 * @param search optional substring to match against the log line
	 * @param req    the current HTTP request (for admin auth check)
	 * @return {@code {ok:true, lines:[...], file:"...", total:n}} on success
	 * @since v2026.1.5
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> getLogs(
			@RequestParam(defaultValue = "200") int lines,
			@RequestParam(defaultValue = "") String level,
			@RequestParam(defaultValue = "") String search,
			HttpServletRequest req) {

		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null) return err;

		int limit = Math.min(Math.max(lines, 1), MAX_LINES);
		File logFile = new File(logFilePath);

		if (!logFile.exists()) {
			return ResponseEntity.ok(Map.of(
					"ok", true,
					"lines", List.of(),
					"file", logFilePath,
					"total", 0,
					"message", "Log file not yet created — app may have just started."
			));
		}

		try {
			List<String> tail = tailFile(logFile, limit * 10); // over-fetch before filtering
			List<Map<String, Object>> parsed = new ArrayList<>();

			String levelUpper = level.toUpperCase(Locale.ROOT);
			String searchLower = search.toLowerCase(Locale.ROOT);

			for (String raw : tail) {
				if (!level.isEmpty() && !raw.contains(levelUpper)) continue;
				if (!search.isEmpty() && !raw.toLowerCase(Locale.ROOT).contains(searchLower)) continue;
				parsed.add(parseLine(raw));
			}

			// Return last `limit` after filtering
			List<Map<String, Object>> result = parsed.size() > limit
					? parsed.subList(parsed.size() - limit, parsed.size())
					: parsed;

			return ResponseEntity.ok(Map.of(
					"ok", true,
					"lines", result,
					"file", logFile.getAbsolutePath(),
					"total", result.size()
			));
		} catch (Exception e) {
			return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
		}
	}

	/**
	 * Returns available log files (current + rotated) so the UI can let the user
	 * pick which file to browse.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return {@code {ok:true, files:[{name, size, lastModified}]}}
	 * @since v2026.1.5
	 */
	@GetMapping("/files")
	public ResponseEntity<Map<String, Object>> listFiles(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null) return err;

		File logDir = new File(logFilePath).getParentFile();
		List<Map<String, Object>> files = new ArrayList<>();

		if (logDir != null && logDir.exists()) {
			File[] logFiles = logDir.listFiles(f ->
					f.getName().endsWith(".log") || f.getName().endsWith(".log.gz"));
			if (logFiles != null) {
				Arrays.sort(logFiles, Comparator.comparingLong(File::lastModified).reversed());
				for (File f : logFiles) {
					files.add(Map.of(
							"name", f.getName(),
							"size", f.length(),
							"lastModified", f.lastModified()
					));
				}
			}
		}

		return ResponseEntity.ok(Map.of("ok", true, "files", files));
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/**
	 * Reads the last {@code maxLines} lines from a file efficiently using a
	 * reverse-scan approach, avoiding loading the entire file into memory.
	 *
	 * @param file     the file to tail
	 * @param maxLines maximum number of lines to return
	 * @return list of lines in chronological order (oldest first)
	 * @throws IOException if the file cannot be read
	 * @author Ashok Ram
	 * @since v2026.1.5
	 */
	private List<String> tailFile(File file, int maxLines) throws IOException {
		LinkedList<String> lines = new LinkedList<>();
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long fileLen = raf.length();
			if (fileLen == 0) return List.of();

			long pos = fileLen - 1;
			StringBuilder sb = new StringBuilder();

			while (pos >= 0 && lines.size() < maxLines) {
				raf.seek(pos);
				int b = raf.read();
				if (b == '\n') {
					if (sb.length() > 0) {
						lines.addFirst(sb.reverse().toString());
						sb.setLength(0);
					}
				} else {
					sb.append((char) b);
				}
				pos--;
			}
			if (sb.length() > 0) lines.addFirst(sb.reverse().toString());
		}
		return lines;
	}

	/**
	 * Parses a single logback-formatted log line into a structured map with
	 * {@code timestamp}, {@code level}, {@code logger}, and {@code message} keys.
	 * Falls back to {@code {raw: line}} if the line doesn't match the expected
	 * format.
	 *
	 * <p>Expected format:
	 * {@code yyyy-MM-dd HH:mm:ss.SSS LEVEL [thread] logger : message}
	 *
	 * @param line the raw log line
	 * @return structured map for JSON serialisation
	 * @author Ashok Ram
	 * @since v2026.1.5
	 */
	private Map<String, Object> parseLine(String line) {
		// Format: 2026-05-25 11:42:50.065 INFO  [thread] logger : message
		try {
			if (line.length() > 24 && line.charAt(10) == ' ' && line.charAt(23) == ' ') {
				String ts = line.substring(0, 23);
				String rest = line.substring(24).stripLeading();

				String lvl = "INFO";
				for (String l : new String[]{"ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
					if (rest.startsWith(l)) {
						lvl = l;
						rest = rest.substring(l.length()).stripLeading();
						break;
					}
				}

				String logger = "";
				String msg = rest;
				int bracket = rest.indexOf('[');
				int closeBracket = rest.indexOf(']');
				int colon = rest.indexOf(" : ");
				if (bracket >= 0 && closeBracket > bracket && colon > closeBracket) {
					logger = rest.substring(closeBracket + 1, colon).strip();
					msg = rest.substring(colon + 3);
				}

				Map<String, Object> m = new LinkedHashMap<>();
				m.put("timestamp", ts);
				m.put("level", lvl);
				m.put("logger", logger);
				m.put("message", msg);
				return m;
			}
		} catch (Exception ignored) {}
		return Map.of("timestamp", "", "level", "INFO", "logger", "", "message", line);
	}
}
