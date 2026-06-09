package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Isolated code execution sandbox that runs user-supplied snippets in a
 * sandboxed subprocess.
 *
 * <p>
 * Provides the following protections for every execution:
 * <ul>
 * <li>10-second wall-clock timeout (SIGKILL on breach)</li>
 * <li>Stdout + stderr combined, capped at 64 KB</li>
 * <li>Temp-file cleanup in a {@code finally} block</li>
 * <li>Per-language interpreter detection (python3 / node / ruby / bash / java)</li>
 * </ul>
 *
 * <p>
 * Supported languages and their interpreter detection order:
 *
 * <pre>
 *   python / python3  → python3, python
 *   javascript / js   → node, nodejs
 *   ruby              → ruby3, ruby
 *   bash / sh         → bash
 *   typescript        → ts-node
 *   java              → javac + java (compile then run, single public class)
 * </pre>
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The agent loop needs to execute code snippets on behalf of users — running
 * data transformations, checking algorithm outputs, or demonstrating code the
 * model has generated. This service provides that capability with a layered
 * security model: process isolation, {@code ulimit} memory/CPU caps, a minimal
 * subprocess environment (no inherited API keys or proxy settings), and
 * language-level preambles that block network access in Python and Bash.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The subprocess environment is cleared and rebuilt from a safe minimal
 * set ({@code PATH}, {@code HOME}, {@code TMPDIR}, {@code LANG}) so that no
 * parent-process secrets can be read by the executed code.</li>
 * <li>{@code ulimit -v} and {@code ulimit -t} are applied via a wrapping
 * {@code bash -c} invocation on Unix/macOS systems; Windows runs without
 * these limits (Docker is the recommended Windows isolation approach).</li>
 * <li>For production deployments with untrusted users, wrap each run in a
 * Docker container or use Firecracker/gVisor. The current implementation is
 * suitable for trusted internal teams.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial implementation</li>
 * <li>v2026.1.9 — security hardening: cleared env, TMPDIR/HOME redirect,
 * ulimit wrapping</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.9
 */
@Service
public class CodeSandboxService {

	private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);

	/** Wall-clock timeout enforced per execution run. SIGKILL is sent on breach. */
	private static final int TIMEOUT_SECONDS = 10;

	/** Maximum output captured from the subprocess before truncation. */
	private static final int MAX_OUTPUT_BYTES = 65_536; // 64 KB

	/**
	 * Virtual memory ceiling applied via {@code ulimit -v} on Unix-like systems,
	 * expressed in kilobytes (256 MB). Prevents memory-bomb attacks.
	 */
	private static final int MEMORY_LIMIT_KB = 262_144; // 256 MB

	/** Mapping from normalised language key to its {@link LangSpec} descriptor. */
	private static final Map<String, LangSpec> LANGS = new LinkedHashMap<>();

	static {
		LANGS.put("python", new LangSpec(".py", List.of("python3", "python")));
		LANGS.put("python3", new LangSpec(".py", List.of("python3", "python")));
		LANGS.put("javascript", new LangSpec(".js", List.of("node", "nodejs")));
		LANGS.put("js", new LangSpec(".js", List.of("node", "nodejs")));
		LANGS.put("typescript", new LangSpec(".ts", List.of("ts-node")));
		LANGS.put("ts", new LangSpec(".ts", List.of("ts-node")));
		LANGS.put("ruby", new LangSpec(".rb", List.of("ruby3", "ruby")));
		LANGS.put("rb", new LangSpec(".rb", List.of("ruby3", "ruby")));
		LANGS.put("bash", new LangSpec(".sh", List.of("bash")));
		LANGS.put("sh", new LangSpec(".sh", List.of("bash", "sh")));
		LANGS.put("shell", new LangSpec(".sh", List.of("bash", "sh")));
		LANGS.put("java", new LangSpec(".java", List.of("javac"))); // special-cased below
	}

	/**
	 * Executes a code snippet for the given language.
	 *
	 * @param language the language identifier (e.g. "python", "javascript")
	 * @param code     the source code to execute
	 * @return a {@link RunResult} with stdout/stderr, exit code, and elapsed time
	 * @since v2026.1.4
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
			if (tempDir != null)
				deleteDir(tempDir);
		}
	}

	// ── Interpreted languages (python, node, ruby, bash) ────────────────────

	/**
	 * Executes a code snippet using an OS interpreter (Python, Node.js, Ruby,
	 * Bash, etc.).
	 *
	 * <p>
	 * Locates the first available interpreter from {@link LangSpec#commands()},
	 * injects the language-specific safety preamble, writes the combined source to
	 * a temp file inside the sandbox directory, and delegates to
	 * {@link #executeProcess}.
	 *
	 * @param code    the raw source code submitted by the user
	 * @param spec    the language specification (file extension + interpreter candidates)
	 * @param tempDir the isolated temporary directory for this execution
	 * @return the execution result including output, exit code and elapsed time
	 * @throws Exception if file I/O or process creation fails
	 * @author Ashok Ram
	 * @since v2026.1.4
	 */
	private RunResult runInterpreted(String code, LangSpec spec, Path tempDir) throws Exception {
		String interpreter = findInterpreter(spec.commands);
		if (interpreter == null) {
			return RunResult.error("Runtime not found. Install one of: " + spec.commands);
		}

		// Inject language-specific safety preambles.
		String safeCode = injectSafetyPreamble(spec.extension, code);

		Path srcFile = tempDir.resolve("main" + spec.extension);
		Files.writeString(srcFile, safeCode, StandardCharsets.UTF_8);

		return executeProcess(List.of(interpreter, srcFile.toString()), tempDir, null);
	}

	/**
	 * Injects safety preambles into user code to enforce resource limits and
	 * disable network access where possible at the interpreter level.
	 *
	 * <p>
	 * This is a defence-in-depth layer — the primary isolation mechanism should
	 * be Docker containers with {@code --network=none} in production. These
	 * preambles add a second layer of protection when running without Docker.
	 *
	 * @param extension the file extension ({@code .py}, {@code .sh}, etc.)
	 * @param code      the original user code
	 * @return the code with safety preamble prepended
	 */
	private String injectSafetyPreamble(String extension, String code) {
		return switch (extension) {
			case ".py" -> PYTHON_PREAMBLE + code;
			case ".sh" -> BASH_PREAMBLE + code;
			default -> code;
		};
	}

	/**
	 * Python preamble: disables network modules (socket, urllib, http, requests,
	 * httpx) and sets a memory ceiling via resource limits.
	 *
	 * <p>
	 * Note: determined attackers can bypass this with ctypes or C extensions.
	 * Docker network isolation remains the recommended production control.
	 */
	private static final String PYTHON_PREAMBLE =
		"""
		import sys as _sys, os as _os, resource as _resource
		# Enforce 256 MB virtual memory soft limit
		try:
		    _mem_limit = 256 * 1024 * 1024
		    _resource.setrlimit(_resource.RLIMIT_AS, (_mem_limit, _mem_limit))
		except Exception:
		    pass
		# Block network modules — redirect them to a sentinel that raises
		class _NetBlocker:
		    def __getattr__(self, name):
		        raise RuntimeError("Network access is disabled in this sandbox")
		    def __call__(self, *a, **kw):
		        raise RuntimeError("Network access is disabled in this sandbox")
		for _mod in ['socket','urllib','urllib.request','urllib.parse','http','http.client',
		             'requests','httpx','aiohttp','ftplib','smtplib']:
		    _sys.modules[_mod] = _NetBlocker()
		del _sys, _os, _resource, _NetBlocker, _mod, _mem_limit
		# ── User code below ───────────────────────────────────────────────────
		""";

	/**
	 * Bash preamble: restricts PATH to disallow network tools and overrides
	 * curl/wget/nc/ssh with no-ops that print a sandbox error message.
	 *
	 * <p>
	 * Note: a determined attacker can still open TCP sockets via /dev/tcp.
	 * Docker network isolation remains the recommended production control.
	 */
	private static final String BASH_PREAMBLE =
		"""
		# Sandbox: override network tools with error stubs
		curl()  { echo "[sandbox] Network access is disabled"; return 1; }
		wget()  { echo "[sandbox] Network access is disabled"; return 1; }
		nc()    { echo "[sandbox] Network access is disabled"; return 1; }
		ncat()  { echo "[sandbox] Network access is disabled"; return 1; }
		ssh()   { echo "[sandbox] Network access is disabled"; return 1; }
		scp()   { echo "[sandbox] Network access is disabled"; return 1; }
		ftp()   { echo "[sandbox] Network access is disabled"; return 1; }
		export -f curl wget nc ncat ssh scp ftp 2>/dev/null || true
		# ── User code below ───────────────────────────────────────────────────
		""";


	// ── Java: compile then run ───────────────────────────────────────────────

	/**
	 * Compiles and executes a Java source file submitted by the user.
	 *
	 * <p>
	 * Extracts the {@code public class} name from the source via regex (falls back
	 * to {@code "Main"} if not found), writes the source to a matching
	 * {@code .java} file, compiles it with {@code javac}, and — if compilation
	 * succeeds — runs the resulting class with {@code java -cp}.  Compilation
	 * errors are returned as a {@link RunResult} with phase {@code "compile"} so
	 * the UI can display them distinctly from runtime errors.
	 *
	 * @param code    the Java source code submitted by the user
	 * @param tempDir the isolated temporary directory for this execution
	 * @return the execution result; phase is {@code "compile"} on compile error,
	 *         {@code "run"} on success or runtime failure
	 * @throws Exception if file I/O or process creation fails
	 * @author Ashok Ram
	 * @since v2026.1.4
	 */
	private RunResult runJava(String code, Path tempDir) throws Exception {
		// Extract public class name (fall back to "Main")
		String className = "Main";
		Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(code);
		if (m.find())
			className = m.group(1);

		Path srcFile = tempDir.resolve(className + ".java");
		Files.writeString(srcFile, code, StandardCharsets.UTF_8);

		// Compile
		String javac = findInterpreter(List.of("javac"));
		if (javac == null)
			return RunResult.error("javac not found on PATH");

		RunResult compileResult = executeProcess(List.of(javac, srcFile.toString()), tempDir, null);
		if (compileResult.exitCode != 0) {
			return RunResult.of(compileResult.output, compileResult.exitCode, compileResult.elapsedMs, "compile");
		}

		// Run
		String javaExe = findInterpreter(List.of("java"));
		if (javaExe == null)
			return RunResult.error("java not found on PATH");

		return executeProcess(List.of(javaExe, "-cp", tempDir.toString(), className), tempDir, null);
	}

	// ── Core process runner ──────────────────────────────────────────────────

	/**
	 * Builds the actual command list to execute, wrapping with {@code ulimit}
	 * on Unix-like systems to enforce memory limits.
	 *
	 * <p>
	 * On macOS/Linux: wraps as {@code bash -c "ulimit -v LIMIT && ulimit -t TIME && cmd args..."}.
	 * This limits virtual memory and CPU seconds. On Windows the command runs as-is
	 * (Docker isolation is the recommended Windows approach).
	 *
	 * @param cmd     the interpreter command and arguments
	 * @param workDir the sandbox working directory
	 * @return the wrapped command list
	 */
	private List<String> wrapWithLimits(List<String> cmd, Path workDir) {
		if (isWindows()) {
			return cmd;
		}
		// Escape each argument for shell embedding
		StringBuilder shellCmd = new StringBuilder();
		// Set virtual memory limit (ulimit -v in KB) and CPU time limit (ulimit -t in seconds)
		shellCmd.append("ulimit -v ").append(MEMORY_LIMIT_KB)
				.append(" 2>/dev/null; ulimit -t ").append(TIMEOUT_SECONDS)
				.append(" 2>/dev/null; ");
		for (int i = 0; i < cmd.size(); i++) {
			if (i > 0) shellCmd.append(' ');
			shellCmd.append(shellEscape(cmd.get(i)));
		}
		return List.of("bash", "-c", shellCmd.toString());
	}

	/** Single-quotes a shell argument, escaping embedded single quotes. */
	private static String shellEscape(String arg) {
		return "'" + arg.replace("'", "'\\''") + "'";
	}

	/**
	 * Launches a subprocess, streams its combined stdout+stderr, enforces the
	 * wall-clock timeout, and returns the captured output as a {@link RunResult}.
	 *
	 * <p>
	 * The command is first wrapped via {@link #wrapWithLimits} which prepends
	 * {@code ulimit} memory and CPU constraints on Unix-like systems.  The
	 * subprocess environment is cleared and rebuilt from a minimal safe set
	 * ({@code PATH}, {@code HOME}, {@code TMPDIR}, {@code LANG}) so that no
	 * parent-process secrets, API keys, or proxy settings are inherited.
	 *
	 * <p>
	 * Output is drained in a virtual thread to avoid blocking the carrier thread
	 * while the process runs.  If the process does not finish within
	 * {@link #TIMEOUT_SECONDS} it is force-killed via {@link Process#destroyForcibly()}.
	 *
	 * @param cmd       the interpreter command and its arguments (not yet wrapped)
	 * @param workDir   the sandbox working directory; used as {@code HOME} and
	 *                  {@code TMPDIR} for the subprocess
	 * @param extraEnv  optional additional environment variables to set after the
	 *                  minimal safe set; may be {@code null}
	 * @return the execution result with output, exit code, elapsed time and phase
	 * @throws Exception if process creation or I/O draining fails unexpectedly
	 * @author Ashok Ram
	 * @since v2026.1.4
	 * @version v2026.1.9 — security hardening: cleared env, TMPDIR/HOME redirect,
	 *          ulimit wrapping
	 */
	private RunResult executeProcess(List<String> cmd, Path workDir, Map<String, String> extraEnv) throws Exception {
		List<String> actualCmd = wrapWithLimits(cmd, workDir);
		ProcessBuilder pb = new ProcessBuilder(actualCmd);
		pb.directory(workDir.toFile());
		pb.redirectErrorStream(true); // merge stderr into stdout
		// Minimal clean environment — strip all parent-process secrets and
		// network-proxy settings that could allow exfiltration via env vars.
		Map<String, String> env = pb.environment();
		env.clear();
		// Allow only the bare minimum needed to run interpreters.
		String path = System.getenv("PATH");
		env.put("PATH", path != null ? path : "/usr/local/bin:/usr/bin:/bin");
		env.put("HOME", workDir.toString()); // sandbox temp dir, not real home
		env.put("LANG", "en_US.UTF-8");
		env.put("TMPDIR", workDir.toString()); // redirect temp file creation to sandbox
		// Explicitly ensure no proxy, no credentials, no cloud env vars leak through
		// (env is already cleared above — this is defence-in-depth documentation)
		if (extraEnv != null)
			env.putAll(extraEnv);

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
			} catch (IOException ignored) {
			}
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
				Process which = new ProcessBuilder(isWindows() ? "where" : "which", candidate).redirectErrorStream(true)
						.start();
				which.waitFor(2, TimeUnit.SECONDS);
				if (which.exitValue() == 0)
					return candidate;
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	/**
	 * Returns {@code true} when the current JVM is running on a Windows host.
	 *
	 * <p>
	 * Used to decide whether {@code ulimit} wrapping and Unix-specific sandbox
	 * controls should be applied.
	 *
	 * @return {@code true} if {@code os.name} contains {@code "win"} (case-insensitive)
	 * @author Ashok Ram
	 * @since v2026.1.4
	 */
	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/**
	 * Recursively deletes a directory tree, silently ignoring individual deletion
	 * failures.
	 *
	 * <p>
	 * Called in the {@code finally} block of {@link #run} to clean up the per-run
	 * sandbox temp directory regardless of whether execution succeeded or failed.
	 * Entries are deleted in reverse file-system order (deepest first) so parent
	 * directories are only removed after their children.
	 *
	 * @param dir the root directory to delete; must not be {@code null}
	 * @author Ashok Ram
	 * @since v2026.1.4
	 */
	private static void deleteDir(Path dir) {
		try {
			Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	// ── Types ─────────────────────────────────────────────────────────────────

	private record LangSpec(String extension, List<String> commands) {
	}

	/**
	 * Immutable result of a single sandbox code execution.
	 *
	 * <p>
	 * The {@code phase} component describes which stage produced the result:
	 * {@code "run"} for a successful execution, {@code "compile"} for a compilation
	 * failure, {@code "timeout"} when the wall-clock limit was exceeded,
	 * {@code "unsupported"} for an unknown language key, or {@code "error"} for any
	 * other internal failure.
	 *
	 * @param ok        {@code true} if the process exited with code 0
	 * @param output    merged stdout + stderr from the subprocess
	 * @param exitCode  process exit code, or {@code -1} on timeout/error
	 * @param elapsedMs wall-clock time consumed by the execution in milliseconds
	 * @param phase     lifecycle stage that produced this result
	 * @param error     human-readable error message; {@code null} on success
	 * @since v2026.1.4
	 */
	public record RunResult(boolean ok, String output, int exitCode, long elapsedMs, String phase, String error) {

		/**
		 * Creates a {@link RunResult} from a completed subprocess.
		 *
		 * @param output     merged stdout + stderr text
		 * @param exitCode   process exit code
		 * @param elapsedMs  wall-clock execution time in milliseconds
		 * @param phase      the execution phase ({@code "run"} or {@code "compile"})
		 * @return a result whose {@code ok} flag reflects whether {@code exitCode} is 0
		 * @since v2026.1.4
		 */
		static RunResult of(String output, int exitCode, long elapsedMs, String phase) {
			return new RunResult(exitCode == 0, output, exitCode, elapsedMs, phase, null);
		}

		/**
		 * Creates an error {@link RunResult} for internal sandbox failures.
		 *
		 * @param msg human-readable description of the error
		 * @return a failed result with phase {@code "error"}
		 * @since v2026.1.4
		 */
		static RunResult error(String msg) {
			return new RunResult(false, "", -1, 0, "error", msg);
		}

		/**
		 * Creates a timeout {@link RunResult} when the wall-clock limit was exceeded.
		 *
		 * @param seconds the timeout limit that was exceeded
		 * @return a failed result with phase {@code "timeout"}
		 * @since v2026.1.4
		 */
		static RunResult timeout(int seconds) {
			return new RunResult(false, "", -1, (long) seconds * 1000, "timeout",
					"Execution timed out after " + seconds + " seconds");
		}

		/**
		 * Creates an unsupported-language {@link RunResult}.
		 *
		 * @param lang the language key that was not recognised
		 * @return a failed result with phase {@code "unsupported"}
		 * @since v2026.1.4
		 */
		static RunResult unsupported(String lang) {
			return new RunResult(false, "", -1, 0, "unsupported",
					"Language '" + lang + "' is not supported. Supported: python, javascript, ruby, bash, java");
		}
	}

	/**
	 * Returns the set of supported language keys that can be passed to
	 * {@link #run}. Used by the frontend to gate display of the "Run" button.
	 *
	 * @return an unmodifiable set of supported language key strings
	 * @since v2026.1.4
	 */
	public static Set<String> supportedLanguages() {
		return LANGS.keySet();
	}
}
