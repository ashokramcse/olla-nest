package com.ollanest.service;

import com.ollanest.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workspace management service: resolves a user's workspace directory, lists
 * project files for the system prompt, extracts code artifacts from model
 * output, and writes artifact files to the local filesystem.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest allows users to configure a local project folder as their
 * workspace. This service centralises all file-system interactions so that
 * controllers and the chat pipeline never touch {@code java.io.File} or
 * {@code java.nio.file} directly. It also hosts the logic for extracting fenced
 * code blocks from model output and writing them to the workspace — a feature
 * that mirrors Olla Nest's "save to workspace" capability in the Node.js
 * implementation.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>File listing is cached for {@value #FILE_CACHE_TTL} milliseconds per
 * workspace root to avoid repeated filesystem traversals during a single
 * streaming session.</li>
 * <li>The file-tree walker is depth-limited to 4 levels and capped at 50 files
 * so large monorepo workspaces do not overwhelm the system-prompt token
 * budget.</li>
 * <li>{@link #cleanModelOutput(String)} strips {@code <think>…</think>} blocks
 * produced by reasoning models before artifact extraction or display.</li>
 * <li>{@link #writeLocalArtifacts} performs a path-traversal guard
 * ({@code filePath.startsWith(root)}) before writing any file, preventing
 * model-generated paths from escaping the workspace root.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from Node.js workspace helpers</li>
 * <li>v2026.1.4 — added {@code filename=} attribute parsing in
 * {@link #FENCE_PATTERN}; added bare HTML detection fallback in
 * {@link #extractArtifacts(String, String)}</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Service
public class WorkspaceService {

	/** JDBC template bound to the application's SQLite data source. */
	private final JdbcTemplate db;

	/**
	 * Provides global settings such as {@code workspaceRoot} and
	 * {@code localWritesEnabled}.
	 */
	private final DatabaseService databaseService;

	/**
	 * Root data directory; resolved from the {@code app.data-dir} property,
	 * defaulting to {@code ./data}. Used as a fallback workspace root when no
	 * per-user preference is stored.
	 */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/**
	 * In-process cache of directory file listings, keyed by absolute workspace root
	 * path.
	 */
	private final ConcurrentHashMap<String, CachedFiles> fileListCache = new ConcurrentHashMap<>();

	/** Cache TTL in milliseconds for workspace file listings. */
	private static final long FILE_CACHE_TTL = 30000;

	/**
	 * Cache entry holding a file listing and the epoch-millisecond at which it was
	 * populated.
	 */
	private static class CachedFiles {
		/** Relative file paths from the workspace root. */
		List<String> files;
		/** Epoch millisecond when this entry was created. */
		long ts;

		CachedFiles(List<String> files, long ts) {
			this.files = files;
			this.ts = ts;
		}
	}

	/**
	 * Constructs the service by injecting its two required collaborators.
	 *
	 * @param db              the JDBC template for SQLite access
	 * @param databaseService the global settings accessor
	 * @since v2026.1.0
	 */
	public WorkspaceService(JdbcTemplate db, DatabaseService databaseService) {
		this.db = db;
		this.databaseService = databaseService;
	}

	/**
	 * Resolves the effective workspace configuration for a user.
	 *
	 * <p>
	 * Looks up the user's per-user workspace preference from
	 * {@code workspace_prefs}. Falls back to the global {@code workspaceRoot}
	 * setting (which itself defaults to {@code <dataDir>/workspace}) if no
	 * preference exists. The workspace root is resolved to an absolute path before
	 * being returned.
	 *
	 * @param userId the user's unique identifier
	 * @return a map with keys {@code workspaceRoot}, {@code outputFolder},
	 *         {@code permissionMode}, and {@code localWritesEnabled}
	 * @since v2026.1.0
	 */
	public Map<String, Object> workspaceForUser(String userId) {
		List<Map<String, Object>> rows = db
				.queryForList("SELECT workspace_root, permission_mode FROM workspace_prefs WHERE user_id = ?", userId);
		String rootSetting = databaseService.getSetting("workspaceRoot", dataDir + "/workspace");
		String root;
		String permMode;
		if (!rows.isEmpty()) {
			root = (String) rows.get(0).get("workspace_root");
			permMode = (String) rows.get(0).get("permission_mode");
		} else {
			root = rootSetting;
			permMode = databaseService.getSetting("localPermissionMode", "default");
		}
		root = Paths.get(root).toAbsolutePath().toString();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("workspaceRoot", root);
		result.put("outputFolder", root + "/olla-nest-output");
		result.put("permissionMode", normalizePermissionMode(permMode));
		result.put("localWritesEnabled", databaseService.getSettingBool("localWritesEnabled", true));
		return result;
	}

	/**
	 * Normalises a permission mode string to one of the three known values.
	 *
	 * @param mode the raw mode string from storage or user input
	 * @return {@code "default"}, {@code "review"}, or {@code "full"}; unknown
	 *         values are coerced to {@code "default"}
	 * @since v2026.1.0
	 */
	public String normalizePermissionMode(String mode) {
		if (Arrays.asList("default", "review", "full").contains(mode))
			return mode;
		return "default";
	}

	/**
	 * Returns a depth-limited, size-capped list of relative file paths under the
	 * given workspace root, using a short-lived in-process cache.
	 *
	 * <p>
	 * The walk skips hidden directories (names starting with {@code .}) and
	 * {@code node_modules}. It stops at depth 4 or after collecting 50 entries,
	 * whichever comes first.
	 *
	 * @param workspaceRoot the absolute path of the workspace directory; may be
	 *                      {@code null} or blank (returns empty list)
	 * @return a list of relative file paths; empty if the directory does not exist
	 *         or cannot be read
	 * @since v2026.1.0
	 */
	public List<String> listWorkspaceFiles(String workspaceRoot) {
		if (workspaceRoot == null || workspaceRoot.isBlank())
			return Collections.emptyList();
		long now = System.currentTimeMillis();
		CachedFiles cached = fileListCache.get(workspaceRoot);
		if (cached != null && now - cached.ts < FILE_CACHE_TTL)
			return cached.files;

		List<String> files = new ArrayList<>();
		Path root = Paths.get(workspaceRoot);
		if (!Files.isDirectory(root))
			return Collections.emptyList();
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				int depth = 0;

				/**
				 * Skips hidden directories, {@code node_modules}, and any subtree deeper
				 * than 4 levels or after 50 files have already been collected.
				 *
				 * {@inheritDoc}
				 */
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (depth > 4 || files.size() >= 50)
						return FileVisitResult.SKIP_SUBTREE;
					String name = dir.getFileName().toString();
					if (dir.equals(root)) {
						depth++;
						return FileVisitResult.CONTINUE;
					}
					if (name.startsWith(".") || "node_modules".equals(name))
						return FileVisitResult.SKIP_SUBTREE;
					depth++;
					return FileVisitResult.CONTINUE;
				}

				/**
				 * Adds the file's path (relative to the workspace root) to the results list,
				 * terminating the walk once 50 files have been collected.
				 *
				 * {@inheritDoc}
				 */
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (files.size() >= 50)
						return FileVisitResult.TERMINATE;
					files.add(root.relativize(file).toString());
					return FileVisitResult.CONTINUE;
				}

				/**
				 * Decrements the depth counter after leaving a directory so that sibling
				 * branches are permitted to proceed.
				 *
				 * {@inheritDoc}
				 */
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
					depth--;
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
		}

		fileListCache.put(workspaceRoot, new CachedFiles(files, now));
		return files;
	}

	/**
	 * Strips {@code <think>…</think>} reasoning blocks from model output.
	 *
	 * <p>
	 * Some models (e.g. DeepSeek-R1) emit internal reasoning wrapped in
	 * {@code <think>} tags before the final response. This method removes those
	 * blocks so they are never shown to the user or included in artifact
	 * extraction. It also handles the case where the response starts with an
	 * unclosed {@code <think>} tag by looking for the first recognisable content
	 * marker (code fence, HTML doctype, React import, etc.).
	 *
	 * @param content the raw model output string; may be {@code null}
	 * @return the cleaned output with {@code <think>} blocks removed; never
	 *         {@code null}
	 * @since v2026.1.0
	 */
	public static String cleanModelOutput(String content) {
		if (content == null)
			return "";
		String output = content.replaceAll("(?si)<think>[\\s\\S]*?</think>", "").replaceAll("(?i)^\\s*</think>\\s*", "")
				.trim();
		if (output.matches("(?i)^<think>[\\s\\S]*")) {
			String lower = output.toLowerCase();
			List<Integer> markers = new ArrayList<>();
			for (String marker : Arrays.asList("```", "<!doctype html", "<html", "import react", "export default")) {
				int idx = lower.indexOf(marker);
				if (idx > 0)
					markers.add(idx);
			}
			if (!markers.isEmpty()) {
				int min = markers.stream().mapToInt(Integer::intValue).min().getAsInt();
				output = output.substring(min).trim();
			} else {
				output = output.replaceFirst("(?i)^<think>", "").trim();
			}
		}
		return output;
	}

	/**
	 * Value object representing a single code artifact extracted from model output.
	 *
	 * @since v2026.1.0
	 */
	public static class Artifact {
		/** Resolved artifact filename (including extension). */
		public String name;
		/** The code or text content of the artifact. */
		public String content;
		/** File extension derived from the fence language tag or content heuristics. */
		public String ext;
		/** Filename as parsed from the code fence header; may be {@code null}. */
		public String parsedFilename;
	}

	/**
	 * Pattern matching fenced code blocks with optional language tag and filename.
	 *
	 * <p>
	 * Capture groups:
	 * <ol>
	 * <li>Language identifier (e.g. {@code html}, {@code jsx})</li>
	 * <li>Filename after {@code :} separator (e.g. {@code ```html:index.html})</li>
	 * <li>Filename from {@code filename="..."} attribute</li>
	 * <li>Block body content</li>
	 * </ol>
	 */
	private static final Pattern FENCE_PATTERN = Pattern
			.compile("```([a-zA-Z0-9_-]*)(?::([^\\s`]+)|[ \\t]+filename=[\"']?([^\"'\\s`]+)[\"']?)?\\n([\\s\\S]*?)```");

	/**
	 * Extracts all fenced code artifacts from model output, falling back to bare
	 * HTML detection if no fences are present.
	 *
	 * <p>
	 * Extraction order:
	 * <ol>
	 * <li>Scan for fenced blocks matching {@link #FENCE_PATTERN}.</li>
	 * <li>If none found, search for a bare {@code <html>…</html>} block.</li>
	 * <li>If still none found and the content looks like code (React/HTML
	 * heuristics), treat the entire content as a single artifact.</li>
	 * </ol>
	 *
	 * <p>
	 * Artifact filenames are resolved in this priority order:
	 * <ol>
	 * <li>Explicit filename from the fence header ({@code :name} or
	 * {@code filename="name"}).</li>
	 * <li>Derived name from {@link #artifactBaseName(String)} plus numeric suffix
	 * for multi-artifact responses.</li>
	 * </ol>
	 *
	 * @param content the model output (already cleaned of {@code <think>} blocks)
	 * @param message the original user message, used for artifact name derivation
	 * @return a list of extracted {@link Artifact} objects; empty if no artifacts
	 *         could be detected
	 * @since v2026.1.0
	 */
	public List<Artifact> extractArtifacts(String content, String message) {
		List<Artifact> artifacts = new ArrayList<>();
		Matcher m = FENCE_PATTERN.matcher(content);
		while (m.find()) {
			String langPart = m.group(1);
			String filenameFromColon = m.group(2);
			String filenameFromAttr = m.group(3);
			String body = m.group(4).trim();
			if (body.isEmpty())
				continue;
			String parsedFilename = filenameFromColon != null ? filenameFromColon : filenameFromAttr;
			Artifact a = new Artifact();
			a.ext = extensionForFence(langPart, body);
			a.content = body;
			a.parsedFilename = parsedFilename;
			artifacts.add(a);
		}
		if (artifacts.isEmpty()) {
			Pattern htmlPat = Pattern.compile("(?i)(?:<!doctype html>\\s*)?<html[\\s\\S]*?</html>");
			Matcher hm = htmlPat.matcher(content);
			if (hm.find()) {
				Artifact a = new Artifact();
				a.ext = "html";
				a.content = hm.group().trim();
				a.parsedFilename = null;
				artifacts.add(a);
			}
		}
		if (artifacts.isEmpty()
				&& content.matches("(?si).*?(<!doctype html|<html[\\s>]|import React|from ['\"]react['\"]"
						+ "|useState|className=|function \\w+|const \\w+\\s*=).*")) {
			Artifact a = new Artifact();
			a.ext = extensionForFence("", content);
			a.content = content.trim();
			a.parsedFilename = null;
			artifacts.add(a);
		}
		String baseName = artifactBaseName(message);
		for (int i = 0; i < artifacts.size(); i++) {
			Artifact a = artifacts.get(i);
			if (a.parsedFilename != null)
				a.name = a.parsedFilename;
			else
				a.name = baseName + (artifacts.size() > 1 ? "-" + (i + 1) : "") + "." + a.ext;
		}
		return artifacts;
	}

	/**
	 * Determines the appropriate file extension for a fenced code block.
	 *
	 * <p>
	 * Recognised language tags map directly to their extension. Unknown or empty
	 * tags fall back to content-based heuristics (HTML doctype or React
	 * indicators). The ultimate fallback is {@code "txt"}.
	 *
	 * @param language the raw language tag from the fence opening (may be blank)
	 * @param content  the fence body, used for heuristic detection
	 * @return a lowercase file extension without the leading dot
	 */
	private String extensionForFence(String language, String content) {
		String lang = language != null ? language.toLowerCase() : "";
		if (Arrays.asList("jsx", "tsx", "ts", "js", "html", "css", "json", "md").contains(lang))
			return lang;
		if (content.matches("(?si)^\\s*<!doctype html|<html[\\s>].*"))
			return "html";
		if (content.matches(
				".*?(import\\s+React|from ['\"]react['\"]|useState|className=|function [A-Z]\\w+|const [A-Z]\\w+\\s*=).*"))
			return "jsx";
		return "txt";
	}

	/**
	 * Derives a URL-friendly base filename from the user's message text.
	 *
	 * <p>
	 * Applies keyword heuristics for common page types (sign-in, dashboard,
	 * landing, component) before falling back to a slugified first line of the
	 * message.
	 *
	 * @param message the original user message; may be {@code null}
	 * @return a URL-safe base name without extension, at most 60 characters
	 */
	private String artifactBaseName(String message) {
		String text = message != null ? message.toLowerCase() : "";
		if (text.matches(".*?sign[\\s-]?in|login.*"))
			return "signin-page";
		if (text.contains("dashboard"))
			return "dashboard";
		if (text.contains("landing"))
			return "landing-page";
		if (text.contains("component"))
			return "component";
		String first = text.split("\n")[0].trim();
		return slugify(first, "generated-output");
	}

	/**
	 * Converts a string to a URL-safe slug by lowercasing, collapsing
	 * non-alphanumeric runs to hyphens, and truncating to 60 characters.
	 *
	 * @param value    the input string; may be {@code null}
	 * @param fallback the value to return if the slug would be empty
	 * @return a non-empty slug string
	 */
	private String slugify(String value, String fallback) {
		if (value == null || value.isBlank())
			return fallback;
		String slug = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (slug.length() > 60)
			slug = slug.substring(0, 60);
		return slug.isEmpty() ? fallback : slug;
	}

	/**
	 * Extracts code artifacts from the model response and writes each one to the
	 * workspace filesystem.
	 *
	 * <p>
	 * Skips writing if {@code localWritesEnabled} is {@code false} in global
	 * settings, or if no artifacts are detected. Applies a path-traversal guard:
	 * any resolved file path that escapes the workspace root is silently skipped.
	 *
	 * @param workspace the user's resolved workspace map (must contain
	 *                  {@code workspaceRoot})
	 * @param message   the original user message (for artifact name derivation)
	 * @param mode      the active chat mode (not currently used but reserved for
	 *                  mode-specific write logic)
	 * @param content   the model output from which artifacts are extracted
	 * @return a list of maps describing each file written, with keys {@code name},
	 *         {@code path}, {@code relativePath}, and {@code bytes}; empty if
	 *         nothing was written
	 * @since v2026.1.4
	 */
	public List<Map<String, Object>> writeLocalArtifacts(Map<String, Object> workspace, String message, String mode,
			String content) {
		if (!databaseService.getSettingBool("localWritesEnabled", true))
			return Collections.emptyList();
		List<Artifact> artifacts = extractArtifacts(content, message);
		if (artifacts.isEmpty())
			return Collections.emptyList();
		String rootStr = (String) workspace.get("workspaceRoot");
		Path root = Paths.get(rootStr).toAbsolutePath();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Artifact artifact : artifacts) {
			Path filePath = root.resolve(artifact.name).toAbsolutePath().normalize();
			if (!filePath.startsWith(root))
				continue; // path traversal guard
			try {
				Files.createDirectories(filePath.getParent());
				Files.writeString(filePath, artifact.content + "\n");
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("name", artifact.name);
				a.put("path", filePath.toString());
				a.put("relativePath", root.relativize(filePath).toString());
				a.put("bytes", artifact.content.getBytes("UTF-8").length);
				result.add(a);
			} catch (Exception e) {
			}
		}
		return result;
	}
}
