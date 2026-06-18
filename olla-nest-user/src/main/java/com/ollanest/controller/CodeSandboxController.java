package com.ollanest.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.CodeSandboxService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Code execution sandbox REST API.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The workspace lets users run code snippets generated in chat and see the
 * output inline. This controller is the HTTP entry point: it validates the
 * request, enforces the execution permission, and delegates to
 * {@link CodeSandboxService}, which runs the code in an isolated subprocess.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Execution runs in an isolated subprocess (ProcessBuilder) with a
 * 10-second wall-clock timeout; stdout and stderr are merged and returned
 * inline.</li>
 * <li><b>Security (CRIT-1):</b> without container isolation the sandbox is
 * effectively OS-level RCE for the JVM user, so {@code /run} is gated on the
 * explicit {@code sandbox:run} right (admins have it implicitly).</li>
 * <li>Input is bounded — language and code are required and code is capped at
 * 50&nbsp;KB — so oversized or empty requests fail fast with a 400.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial sandbox API; CRIT-1 added the {@code sandbox:run}
 * permission gate</li>
 * </ul>
 *
 * <pre>
 *   POST /api/sandbox/run        — execute a code snippet, return stdout+stderr
 *   GET  /api/sandbox/languages  — list supported language keys
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 */
@RestController
@RequestMapping("/api/sandbox")
public class CodeSandboxController extends BaseController {

	/** Service that compiles and runs code snippets in isolated subprocesses. */
	private final CodeSandboxService sandboxService;

	/**
	 * Constructor-injects the code sandbox service.
	 *
	 * @param sandboxService the service responsible for executing code snippets
	 * @since v2026.1.4
	 */
	public CodeSandboxController(CodeSandboxService sandboxService) {
		this.sandboxService = sandboxService;
	}

	/**
	 * Executes a code snippet in the sandbox.
	 *
	 * @param body JSON: {@code { language: "python", code: "print('hi')" }}
	 * @param req  HTTP request — auth + CSRF header required
	 * @return 200 with {@code { ok, output, exitCode, elapsedMs, phase }}; 400 for
	 *         missing/oversized input; 403 without the {@code sandbox:run} right
	 * @since v2026.1.4
	 */
	@PostMapping("/run")
	public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body, HttpServletRequest req) {

		ResponseEntity<Map<String, Object>> authErr = guardAuthWithCsrf(req);
		if (authErr != null)
			return authErr;

		// CRIT-1 MITIGATION: Gate sandbox execution on an explicit 'sandbox:run' right.
		// Without Docker/container isolation the sandbox is OS-level RCE for the JVM
		// user.
		// Admins implicitly have all rights; regular users need 'sandbox:run' granted
		// explicitly.
		User sandboxUser = getUser(req);
		boolean hasSandboxRight = "admin".equals(sandboxUser.role)
				|| (sandboxUser.rights != null && sandboxUser.rights.contains("sandbox:run"));
		if (!hasSandboxRight) {
			return ResponseEntity.status(403).body(Map.of("ok", false, "error",
					"Code execution requires the 'sandbox:run' permission. Contact your administrator."));
		}

		String language = (String) body.getOrDefault("language", "");
		String code = (String) body.getOrDefault("code", "");

		if (language.isBlank())
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "language is required"));
		if (code.isBlank())
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code is required"));
		if (code.length() > 50_000)
			return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code too large (max 50 KB)"));

		CodeSandboxService.RunResult result = sandboxService.run(language, code);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("ok", result.ok());
		resp.put("output", result.output());
		resp.put("exitCode", result.exitCode());
		resp.put("elapsedMs", result.elapsedMs());
		resp.put("phase", result.phase());
		if (result.error() != null)
			resp.put("error", result.error());
		return ResponseEntity.ok(resp);
	}

	/**
	 * Returns the set of language keys the sandbox accepts.
	 *
	 * <p>
	 * Used by the frontend "Run" button to determine which languages are available
	 * without hard-coding the list in the client.
	 *
	 * @param req the current HTTP request (authentication required)
	 * @return 200 with {@code { ok: true, languages: [...] }}
	 * @since v2026.1.4
	 */
	@GetMapping("/languages")
	public ResponseEntity<Map<String, Object>> languages(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authErr = guardAuth(req);
		if (authErr != null)
			return authErr;
		Set<String> langs = CodeSandboxService.supportedLanguages();
		return ResponseEntity.ok(Map.of("ok", true, "languages", langs));
	}
}
