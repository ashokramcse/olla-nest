package com.ollanest.service;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PromptTemplateService {

    private static final String BASE_TEMPLATE = """
        {identity}
        Selected model: {model}. Routing reason: {reason}.
        {workspaceSection}
        {projectKnowledgeSection}
        {ragSection}
        Mode: {mode}
        {modeInstruction}
        """;

    private static final Map<String, String> MODE_INSTRUCTIONS = new LinkedHashMap<>();

    static {
        MODE_INSTRUCTIONS.put("ask",
            "Give a clear, complete, immediately useful answer. Include examples where helpful. Do not pad with unnecessary caveats.");
        MODE_INSTRUCTIONS.put("build",
            "Build the requested output. Return the complete implementation as a fenced code block with the filename in the header (e.g. ```html:index.html). One complete, runnable file. Do not return only a plan.");
        MODE_INSTRUCTIONS.put("review",
            "Review the request for issues, risks, improvements, and missing pieces. Structure: (1) Critical issues, (2) Improvements, (3) What's good. Lead with actionable findings.");
        MODE_INSTRUCTIONS.put("fix",
            "Diagnose the root cause. Show the exact broken code, explain precisely why it fails, then provide the corrected version. Include verification steps.");
        MODE_INSTRUCTIONS.put("debug",
            "Identify the root cause of the error. Show the exact line that is wrong, explain why it fails, then provide a corrected version. Include a checklist of other things to verify.");
        MODE_INSTRUCTIONS.put("test",
            "Write comprehensive tests: unit, integration, edge cases, and error cases. Use the most appropriate test framework. Add brief comments on what each test covers.");
        MODE_INSTRUCTIONS.put("docs",
            "Generate complete documentation: purpose, parameters, return values, usage examples, important notes. For a project write a professional README with setup, usage, and API reference.");
        MODE_INSTRUCTIONS.put("plan",
            "Break this into a clear, opinionated implementation plan: recommended tech stack with reasoning, folder/file structure, step-by-step build order, key decisions, and estimated complexity per phase.");
        MODE_INSTRUCTIONS.put("learn",
            "Teach the concept with a progression from fundamentals to depth. Use analogies and concrete examples. End with the most important thing to remember.");
    }

    public String buildSystemPrompt(String mode, String modelName, String routeReason,
                                     String workspaceInfo, String projectKnowledge,
                                     String ragContext) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("identity", "You are Olla Nest, a company AI workspace assistant. Answer the user's request directly and completely. Do not include hidden thinking, <think> blocks, or internal reasoning traces.");
        vars.put("model", modelName != null ? modelName : "auto");
        vars.put("reason", routeReason != null ? routeReason : "auto-routed");
        vars.put("mode", mode != null ? mode : "ask");
        vars.put("modeInstruction", MODE_INSTRUCTIONS.getOrDefault(mode, MODE_INSTRUCTIONS.get("ask")));
        vars.put("workspaceSection", workspaceInfo != null && !workspaceInfo.isBlank() ? workspaceInfo : "");
        vars.put("projectKnowledgeSection", projectKnowledge != null && !projectKnowledge.isBlank()
            ? "Project Knowledge:\n" + projectKnowledge.trim() : "");
        vars.put("ragSection", ragContext != null && !ragContext.isBlank() ? ragContext : "");

        PromptTemplate pt = new PromptTemplate(BASE_TEMPLATE);
        return pt.render(vars).trim();
    }

    public Map<String, String> getModeInstructions() {
        return Collections.unmodifiableMap(MODE_INSTRUCTIONS);
    }
}
