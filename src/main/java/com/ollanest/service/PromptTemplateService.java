package com.ollanest.service;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Assembles the system prompt for each chat turn by combining an identity
 * preamble, routing metadata, workspace context, project knowledge, RAG
 * snippets, and mode-specific behaviour instructions.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The system prompt is the primary mechanism by which Olla Nest shapes model
 * behaviour. Centralising prompt construction here ensures that every code path
 * (streaming, non-streaming, function-call follow-up) produces the same prompt
 * shape, and makes it easy to iterate on wording without touching controller
 * logic.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The template uses Spring AI's {@link PromptTemplate} for variable
 * substitution. All variables that may be absent are substituted with an empty
 * string so the template never renders literal {@code {placeholder}} text.</li>
 * <li>Mode instructions are stored in a static {@link LinkedHashMap} so
 * iteration order matches insertion order, which is useful when listing modes
 * in the UI.</li>
 * <li>Unknown mode values fall back to the {@code "ask"} instruction set so the
 * prompt is always well-formed even if a client sends an unrecognised
 * mode.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration; extracted from the Node.js chat service
 * prompt-building logic</li>
 * <li>v2026.1.4 — added {@code ragSection} placeholder and the {@code learn}
 * mode instruction</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Service
public class PromptTemplateService {

	/**
	 * Spring AI template string with named placeholders for each prompt section.
	 */
	private static final String BASE_TEMPLATE = """
			{identity}
			Selected model: {model}. Routing reason: {reason}.
			{workspaceSection}
			{projectKnowledgeSection}
			{ragSection}
			Mode: {mode}
			{modeInstruction}
			""";

	/**
	 * Per-mode behaviour instructions injected into the system prompt. Insertion
	 * order is preserved for UI display purposes.
	 */
	private static final Map<String, String> MODE_INSTRUCTIONS = new LinkedHashMap<>();

	static {
		MODE_INSTRUCTIONS.put("ask",
				"Give a clear, complete, immediately useful answer. Include examples where helpful. "
						+ "Do not pad with unnecessary caveats.");
		MODE_INSTRUCTIONS.put("build",
				"Build the requested output. Return the complete implementation as a fenced code block with the "
						+ "filename in the header (e.g. ```html:index.html). One complete, runnable file. Do not return only a plan.");
		MODE_INSTRUCTIONS.put("review", "Review the request for issues, risks, improvements, and missing pieces. "
				+ "Structure: (1) Critical issues, (2) Improvements, (3) What's good. Lead with actionable findings.");
		MODE_INSTRUCTIONS.put("fix",
				"Diagnose the root cause. Show the exact broken code, explain precisely why it fails, then provide the "
						+ "corrected version. Include verification steps.");
		MODE_INSTRUCTIONS.put("debug",
				"Identify the root cause of the error. Show the exact line that is wrong, explain why it fails, then "
						+ "provide a corrected version. Include a checklist of other things to verify.");
		MODE_INSTRUCTIONS.put("test",
				"Write comprehensive tests: unit, integration, edge cases, and error cases. Use the most appropriate "
						+ "test framework. Add brief comments on what each test covers.");
		MODE_INSTRUCTIONS.put("docs",
				"Generate complete documentation: purpose, parameters, return values, usage examples, important notes. "
						+ "For a project write a professional README with setup, usage, and API reference.");
		MODE_INSTRUCTIONS.put("plan",
				"Break this into a clear, opinionated implementation plan: recommended tech stack with reasoning, "
						+ "folder/file structure, step-by-step build order, key decisions, and estimated complexity per phase.");
		MODE_INSTRUCTIONS.put("learn",
				"Teach the concept with a progression from fundamentals to depth. Use analogies and concrete examples. "
						+ "End with the most important thing to remember.");
	}

	/**
	 * Builds the complete system prompt string for a single chat turn.
	 *
	 * <p>
	 * Variable substitution rules:
	 * <ul>
	 * <li>{@code identity} — always set to the Olla Nest assistant preamble</li>
	 * <li>{@code model} — defaults to {@code "auto"} if {@code null}</li>
	 * <li>{@code reason} — defaults to {@code "auto-routed"} if {@code null}</li>
	 * <li>{@code mode} — defaults to {@code "ask"} if {@code null}</li>
	 * <li>{@code modeInstruction} — resolved from {@link #MODE_INSTRUCTIONS};
	 * unknown modes fall back to {@code "ask"}</li>
	 * <li>{@code workspaceSection} — empty string if {@code null} or blank</li>
	 * <li>{@code projectKnowledgeSection} — empty string if {@code null} or blank;
	 * otherwise prefixed with {@code "Project Knowledge:\n"}</li>
	 * <li>{@code ragSection} — empty string if {@code null} or blank</li>
	 * </ul>
	 *
	 * @param mode             the active chat mode; may be {@code null}
	 * @param modelName        the display name of the selected model; may be
	 *                         {@code null}
	 * @param routeReason      the human-readable routing decision reason; may be
	 *                         {@code null}
	 * @param workspaceInfo    pre-formatted workspace context block; may be
	 *                         {@code null} or blank
	 * @param projectKnowledge free-text project knowledge; may be {@code null} or
	 *                         blank
	 * @param ragContext       pre-formatted RAG retrieval context; may be
	 *                         {@code null} or blank
	 * @return the fully assembled, trimmed system prompt string
	 * @since v2026.1.0
	 */
	public String buildSystemPrompt(String mode, String modelName, String routeReason, String workspaceInfo,
			String projectKnowledge, String ragContext) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("identity",
				"You are Olla Nest, a company AI workspace assistant. Answer the user's request directly and completely. "
						+ "Do not include hidden thinking, <think> blocks, or internal reasoning traces.");
		vars.put("model", modelName != null ? modelName : "auto");
		vars.put("reason", routeReason != null ? routeReason : "auto-routed");
		vars.put("mode", mode != null ? mode : "ask");
		vars.put("modeInstruction", MODE_INSTRUCTIONS.getOrDefault(mode, MODE_INSTRUCTIONS.get("ask")));
		vars.put("workspaceSection", workspaceInfo != null && !workspaceInfo.isBlank() ? workspaceInfo : "");
		vars.put("projectKnowledgeSection",
				projectKnowledge != null && !projectKnowledge.isBlank()
						? "Project Knowledge:\n" + projectKnowledge.trim()
						: "");
		vars.put("ragSection", ragContext != null && !ragContext.isBlank() ? ragContext : "");

		PromptTemplate pt = new PromptTemplate(BASE_TEMPLATE);
		return pt.render(vars).trim();
	}

	/**
	 * Returns an unmodifiable view of the per-mode instruction strings.
	 *
	 * <p>
	 * Useful for admin endpoints that expose the list of available modes and their
	 * instructions to the frontend.
	 *
	 * @return an unmodifiable {@link Map} from mode name to instruction text, in
	 *         insertion order
	 * @since v2026.1.0
	 */
	public Map<String, String> getModeInstructions() {
		return Collections.unmodifiableMap(MODE_INSTRUCTIONS);
	}
}
