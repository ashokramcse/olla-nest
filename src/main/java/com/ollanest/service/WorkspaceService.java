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
 * Workspace helpers: workspaceForUser, listWorkspaceFiles, writeLocalArtifacts,
 * extractArtifacts, cleanModelOutput.
 */
@Service
public class WorkspaceService {

    private final JdbcTemplate db;
    private final DatabaseService databaseService;

    @Value("${app.data-dir:./data}")
    private String dataDir;

    // Cache: path -> {files, timestamp}
    private final ConcurrentHashMap<String, CachedFiles> fileListCache = new ConcurrentHashMap<>();
    private static final long FILE_CACHE_TTL = 30000;

    private static class CachedFiles {
        List<String> files;
        long ts;
        CachedFiles(List<String> files, long ts) { this.files = files; this.ts = ts; }
    }

    public WorkspaceService(JdbcTemplate db, DatabaseService databaseService) {
        this.db = db;
        this.databaseService = databaseService;
    }

    public Map<String, Object> workspaceForUser(String userId) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT workspace_root, permission_mode FROM workspace_prefs WHERE user_id = ?", userId);
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

    public String normalizePermissionMode(String mode) {
        if (Arrays.asList("default", "review", "full").contains(mode)) return mode;
        return "default";
    }

    public List<String> listWorkspaceFiles(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        CachedFiles cached = fileListCache.get(workspaceRoot);
        if (cached != null && now - cached.ts < FILE_CACHE_TTL) return cached.files;

        List<String> files = new ArrayList<>();
        Path root = Paths.get(workspaceRoot);
        if (!Files.isDirectory(root)) return Collections.emptyList();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                int depth = 0;
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (depth > 4 || files.size() >= 50) return FileVisitResult.SKIP_SUBTREE;
                    String name = dir.getFileName().toString();
                    if (dir.equals(root)) { depth++; return FileVisitResult.CONTINUE; }
                    if (name.startsWith(".") || "node_modules".equals(name)) return FileVisitResult.SKIP_SUBTREE;
                    depth++;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= 50) return FileVisitResult.TERMINATE;
                    files.add(root.relativize(file).toString());
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    depth--;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {}

        fileListCache.put(workspaceRoot, new CachedFiles(files, now));
        return files;
    }

    public static String cleanModelOutput(String content) {
        if (content == null) return "";
        String output = content.replaceAll("(?si)<think>[\\s\\S]*?</think>", "")
                               .replaceAll("(?i)^\\s*</think>\\s*", "")
                               .trim();
        if (output.matches("(?i)^<think>[\\s\\S]*")) {
            String lower = output.toLowerCase();
            List<Integer> markers = new ArrayList<>();
            for (String marker : Arrays.asList("```", "<!doctype html", "<html", "import react", "export default")) {
                int idx = lower.indexOf(marker);
                if (idx > 0) markers.add(idx);
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

    public static class Artifact {
        public String name;
        public String content;
        public String ext;
        public String parsedFilename;
    }

    private static final Pattern FENCE_PATTERN = Pattern.compile(
        "```([a-zA-Z0-9_-]*)(?::([^\\s`]+)|[ \\t]+filename=[\"']?([^\"'\\s`]+)[\"']?)?\\n([\\s\\S]*?)```");

    public List<Artifact> extractArtifacts(String content, String message) {
        List<Artifact> artifacts = new ArrayList<>();
        Matcher m = FENCE_PATTERN.matcher(content);
        while (m.find()) {
            String langPart = m.group(1);
            String filenameFromColon = m.group(2);
            String filenameFromAttr = m.group(3);
            String body = m.group(4).trim();
            if (body.isEmpty()) continue;
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
                a.ext = "html"; a.content = hm.group().trim(); a.parsedFilename = null;
                artifacts.add(a);
            }
        }
        if (artifacts.isEmpty() && content.matches("(?si).*?(<!doctype html|<html[\\s>]|import React|from ['\"]react['\"]|useState|className=|function \\w+|const \\w+\\s*=).*")) {
            Artifact a = new Artifact();
            a.ext = extensionForFence("", content); a.content = content.trim(); a.parsedFilename = null;
            artifacts.add(a);
        }
        String baseName = artifactBaseName(message);
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            if (a.parsedFilename != null) a.name = a.parsedFilename;
            else a.name = baseName + (artifacts.size() > 1 ? "-" + (i + 1) : "") + "." + a.ext;
        }
        return artifacts;
    }

    private String extensionForFence(String language, String content) {
        String lang = language != null ? language.toLowerCase() : "";
        if (Arrays.asList("jsx", "tsx", "ts", "js", "html", "css", "json", "md").contains(lang)) return lang;
        if (content.matches("(?si)^\\s*<!doctype html|<html[\\s>].*")) return "html";
        if (content.matches(".*?(import\\s+React|from ['\"]react['\"]|useState|className=|function [A-Z]\\w+|const [A-Z]\\w+\\s*=).*")) return "jsx";
        return "txt";
    }

    private String artifactBaseName(String message) {
        String text = message != null ? message.toLowerCase() : "";
        if (text.matches(".*?sign[\\s-]?in|login.*")) return "signin-page";
        if (text.contains("dashboard")) return "dashboard";
        if (text.contains("landing")) return "landing-page";
        if (text.contains("component")) return "component";
        String first = text.split("\n")[0].trim();
        return slugify(first, "generated-output");
    }

    private String slugify(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String slug = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 60) slug = slug.substring(0, 60);
        return slug.isEmpty() ? fallback : slug;
    }

    public List<Map<String, Object>> writeLocalArtifacts(Map<String, Object> workspace, String message, String mode, String content) {
        if (!databaseService.getSettingBool("localWritesEnabled", true)) return Collections.emptyList();
        List<Artifact> artifacts = extractArtifacts(content, message);
        if (artifacts.isEmpty()) return Collections.emptyList();
        String rootStr = (String) workspace.get("workspaceRoot");
        Path root = Paths.get(rootStr).toAbsolutePath();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            Path filePath = root.resolve(artifact.name).toAbsolutePath().normalize();
            if (!filePath.startsWith(root)) continue; // path traversal guard
            try {
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, artifact.content + "\n");
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", artifact.name);
                a.put("path", filePath.toString());
                a.put("relativePath", root.relativize(filePath).toString());
                a.put("bytes", artifact.content.getBytes("UTF-8").length);
                result.add(a);
            } catch (Exception e) {}
        }
        return result;
    }
}
