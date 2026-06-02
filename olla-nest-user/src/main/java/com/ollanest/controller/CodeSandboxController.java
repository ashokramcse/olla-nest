package com.ollanest.controller;

import com.ollanest.service.CodeSandboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Code execution sandbox REST API.
 *
 * <pre>
 *   POST /api/sandbox/run        — execute a code snippet, return stdout+stderr
 *   GET  /api/sandbox/languages  — list supported language keys
 * </pre>
 *
 * <p>
 * Runs code in an isolated subprocess (ProcessBuilder) with a 10-second
 * wall-clock timeout. Stdout and stderr are merged and returned inline so the
 * frontend can display output directly beneath the code block that triggered
 * the run.
 *
 * <p>
 * Access control: any authenticated user may run code. For tighter control,
 * gate on the {@code sandbox:run} right in the user's rights list.
 *
 * @author Ashok Ram
 * @since v2026.1.4
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
	 * @return 200 with {@code { ok, output, exitCode, elapsedMs, phase }}
	 */
	@PostMapping("/run")
	public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body, HttpServletRequest req) {

		ResponseEntity<Map<String, Object>> authErr = requireAuthWithCsrf(req);
		if (authErr != null)
			return authErr;

		// CRIT-1 MITIGATION: Gate sandbox execution on an explicit 'sandbox:run' right.
		// Without Docker/container isolation the sandbox is OS-level RCE for the JVM user.
		// Admins implicitly have all rights; regular users need 'sandbox:run' granted explicitly.
		com.ollanest.model.User sandboxUser = getUser(req);
		boolean hasSandboxRight = "admin".equals(sandboxUser.role)
			|| (sandboxUser.rights != null && sandboxUser.rights.contains("sandbox:run"));
		if (!hasSandboxRight) {
			return ResponseEntity.status(403).body(Map.of("ok", false,
				"error", "Code execution requires the 'sandbox:run' permission. Contact your administrator."));
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
