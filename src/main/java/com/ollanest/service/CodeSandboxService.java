package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Isolated code execution sandbox.
 *
 * <p>Executes user-supplied code snippets in a sandboxed subprocess with:
 * <ul>
 *   <li>10-second wall-clock timeout (SIGKILL on breach)</li>
 *   <li>Stdout + stderr combined, capped at 64 KB</li>
 *   <li>Temp-file cleanup after every run</li>
 *   <li>Per-language interpreter detection (python3 / node / ruby / bash / java)</li>
 * </ul>
 *
 * <p>Supported languages and their interpreter detection order:
 * <pre>
 *   python / python3  → python3, python
 *   javascript / js   → node, nodejs
 *   ruby              → ruby3, ruby
 *   bash / sh         → bash
 *   typescript        → ts-node, npx ts-node
 *   java              → javac + java (compile + run, single public class)
 * </pre>
 *
 * <p>Security notes: This service uses OS-level process isolation only.
 * For production use, wrap each run in a Docker container or use a dedicated
 * sandbox runtime (Firecracker, gVisor). The current implementation is suitable
 * for trusted internal teams.
 *
 * @author  Ashok Ram
 * @since   v2026.1.4
 */
@Service
public class CodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);

    /** Wall-clock timeout per execution (seconds). */
    private static final int TIMEOUT_SECONDS = 10;

    /** Maximum output returned to caller (bytes). */
    private static final int MAX_OUTPUT_BYTES = 65_536; // 64 KB

    /** Maps normalised language key → (extension, command-candidates). */
    private static final Map<String, LangSpec> LANGS = new LinkedHashMap<>();

    static {
        LANGS.put("python",     new LangSpec(".py",   List.of("python3", "python")));
        LANGS.put("python3",    new LangSpec(".py",   List.of("python3", "python")));
        LANGS.put("javascript", new LangSpec(".js",   List.of("node", "nodejs")));
        LANGS.put("js",         new LangSpec(".js",   List.of("node", "nodejs")));
        LANGS.put("typescript", new LangSpec(".ts",   List.of("ts-node")));
        LANGS.put("ts",         new LangSpec(".ts",   List.of("ts-node")));
        LANGS.put("ruby",       new LangSpec(".rb",   List.of("ruby3", "ruby")));
        LANGS.put("rb",         new LangSpec(".rb",   List.of("ruby3", "ruby")));
        LANGS.put("bash",       new LangSpec(".sh",   List.of("bash")));
        LANGS.put("sh",         new LangSpec(".sh",   List.of("bash", "sh")));
        LANGS.put("shell",      new LangSpec(".sh",   List.of("bash", "sh")));
        LANGS.put("java",       new LangSpec(".java", List.of("javac"))); // special-cased below
    }

    /**
     * Executes a code snippet for the given language.
     *
     * @param  language  the language identifier (e.g. "python", "javascript")
     * @param  code      the source code to execute
     * @return           a {@link RunResult} with stdout/stderr, exit code, and elapsed time
     */
    public RunResult run(String language, String code) {
        String lang = language == null ? "" : language.toLowerCase().trim();
        LangSpec spec = LANGS.get(lang);
        if (spec == null) {
            return RunResult.unsupported(lang);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("olla-sandbox-");
            if ("java".equals(lang)) {
                return runJava(code, tempDir);
            }
            return runInterpreted(code, spec, tempDir);
        } catch (Exception e) {
            log.error("[sandbox] run error lang={}: {}", lang, e.getMessage());
            return RunResult.error("Sandbox error: " + e.getMessage());
        } finally {
            if (tempDir != null) deleteDir(tempDir);
        }
    }

    // ── Interpreted languages (python, node, ruby, bash) ────────────────────

    private RunResult runInterpreted(String code, LangSpec spec, Path tempDir) throws Exception {
        String interpreter = findInterpreter(spec.commands);
        if (interpreter == null) {
            return RunResult.error("Runtime not found. Install one of: " + spec.commands);
        }

        Path srcFile = tempDir.resolve("main" + spec.extension);
        Files.writeString(srcFile, code, StandardCharsets.UTF_8);

        return executeProcess(List.of(interpreter, srcFile.toString()), tempDir, null);
    }

    // ── Java: compile then run ───────────────────────────────────────────────

    private RunResult runJava(String code, Path tempDir) throws Exception {
        // Extract public class name (fall back to "Main")
        String className = "Main";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("public\\s+class\\s+(\\w+)").matcher(code);
        if (m.find()) className = m.group(1);

        Path srcFile = tempDir.resolve(className + ".java");
        Files.writeString(srcFile, code, StandardCharsets.UTF_8);

        // Compile
        String javac = findInterpreter(List.of("javac"));
        if (javac == null) return RunResult.error("javac not found on PATH");

        RunResult compileResult = executeProcess(
                List.of(javac, srcFile.toString()), tempDir, null);
        if (compileResult.exitCode != 0) {
            return RunResult.of(compileResult.output, compileResult.exitCode,
                    compileResult.elapsedMs, "compile");
        }

        // Run
        String javaExe = findInterpreter(List.of("java"));
        if (javaExe == null) return RunResult.error("java not found on PATH");

        return executeProcess(
                List.of(javaExe, "-cp", tempDir.toString(), className),
                tempDir, null);
    }

    // ── Core process runner ──────────────────────────────────────────────────

    private RunResult executeProcess(List<String> cmd, Path workDir, Map<String, String> extraEnv)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true); // merge stderr into stdout
        // Clean minimal environment — no secrets from parent process
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", System.getenv("PATH") != null ? System.getenv("PATH") : "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", System.getProperty("user.home", "/tmp"));
        env.put("LANG", "en_US.UTF-8");
        if (extraEnv != null) env.putAll(extraEnv);

        long startMs = System.currentTimeMillis();
        Process process = pb.start();

        // Drain stdout+stderr in a virtual thread to avoid blocking
        StringBuilder output = new StringBuilder();
        Future<Void> drainer = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (output.length() < MAX_OUTPUT_BYTES) {
                        output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {}
            return null;
        });

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            drainer.cancel(true);
            return RunResult.timeout(TIMEOUT_SECONDS);
        }
        drainer.get(2, TimeUnit.SECONDS);

        long elapsedMs = System.currentTimeMillis() - startMs;
        String out = output.toString();
        if (out.length() >= MAX_OUTPUT_BYTES) {
            out += "\n[... output truncated at 64 KB ...]";
        }
        return RunResult.of(out, process.exitValue(), elapsedMs, "run");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the first command from candidates that exists on PATH, or null. */
    private String findInterpreter(List<String> candidates) {
        for (String candidate : candidates) {
            try {
                Process which = new ProcessBuilder(isWindows() ? "where" : "which", candidate)
                        .redirectErrorStream(true).start();
                which.waitFor(2, TimeUnit.SECONDS);
                if (which.exitValue() == 0) return candidate;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    private record LangSpec(String extension, List<String> commands) {}

    /** Result of a sandbox execution. */
    public record RunResult(
            boolean ok,
            String output,
            int exitCode,
            long elapsedMs,
            String phase,    // "run" | "compile" | "timeout" | "unsupported" | "error"
            String error
    ) {
        static RunResult of(String output, int exitCode, long elapsedMs, String phase) {
            return new RunResult(exitCode == 0, output, exitCode, elapsedMs, phase, null);
        }
        static RunResult error(String msg) {
            return new RunResult(false, "", -1, 0, "error", msg);
        }
        static RunResult timeout(int seconds) {
            return new RunResult(false, "", -1, (long) seconds * 1000,
                    "timeout", "Execution timed out after " + seconds + " seconds");
        }
        static RunResult unsupported(String lang) {
            return new RunResult(false, "", -1, 0, "unsupported",
                    "Language '" + lang + "' is not supported. Supported: python, javascript, ruby, bash, java");
        }
    }

    /**
     * Returns the set of supported language keys (for the frontend "Run" button check).
     */
    public static Set<String> supportedLanguages() {
        return LANGS.keySet();
    }
}
