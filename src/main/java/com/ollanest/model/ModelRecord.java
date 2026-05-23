package com.ollanest.model;

import java.util.List;

/**
 * Immutable value object representing a model row from the {@code models} table.
 *
 * <h3>Why this class exists</h3>
 * <p>The router, provider layer, and chat pipeline all need model metadata —
 * capability tags, speed/quality scores, privacy classification, context window
 * size, and governance attributes — for every routing decision. {@link ModelRecord}
 * provides a typed carrier for this data, replacing ad-hoc {@code Map<String,Object>}
 * access and eliminating repeated DB lookups by allowing the record to be cached and
 * passed by reference.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>Plain public fields (no getters/setters) keep deserialization simple and
 *       avoid Jackson configuration overhead when records are serialised to JSON for
 *       API responses.</li>
 *   <li>{@link #contextSize} is a boxed {@code Long} so it can be {@code null} when
 *       the Ollama manifest does not report a context window, distinguishing "unknown"
 *       from "zero".</li>
 *   <li>Fields map 1:1 to DB columns and are populated by
 *       {@code ModelService.parseModel()} from a JDBC result-set map.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial migration; replaces Node.js plain object model rows</li>
 *   <li>v2026.1.4 — added {@link #externalCostTier} and {@link #sensitiveAllowed}
 *       fields to support governance-policy routing</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
public class ModelRecord {

    /** Unique model identifier (UUID primary key from the {@code models} table). */
    public String id;

    /** Human-readable display name shown in the UI selector. */
    public String name;

    /** Provider key, e.g. {@code "ollama"}, {@code "anthropic"}, {@code "openai"}. */
    public String provider;

    /**
     * Model reference string passed to the provider API, e.g. {@code "llama3:8b"} or
     * {@code "claude-opus-4-5"}.
     */
    public String model;

    /** Lifecycle status: {@code "available"}, {@code "disabled"}, or {@code "pending"}. */
    public String status;

    /** Capability tags used by the router for task-based model selection, e.g.
     *  {@code ["code", "reasoning"]}. */
    public List<String> capabilities;

    /** Router speed score (0–100); higher values indicate a faster expected response. */
    public int speedScore;

    /** Router quality score (0–100); higher values indicate better expected output quality. */
    public int qualityScore;

    /**
     * Privacy classification: {@code "local"} (on-premises Ollama) or
     * {@code "external"} (cloud provider). The router uses this to enforce the
     * privacy gate when sensitive content is detected.
     */
    public String privacy;

    /** ISO-8601 timestamp of the last time this model was seen alive by the model poller. */
    public String lastSeenAt;

    /** Governance tier controlling which user roles may access this model. */
    public String governanceTier;

    /**
     * Resource tier used for quota accounting, e.g. {@code "standard"} or {@code "gpu"}.
     */
    public String resourceTier;

    /** Whether this model requires a GPU to run; used for scheduling and routing decisions. */
    public boolean gpuRequired;

    /** Maximum number of simultaneous in-flight requests allowed for this model. */
    public int maxConcurrency;

    /** Hard maximum context size in tokens enforced by the governance policy layer. */
    public long maxContextSize;

    /**
     * Actual context window reported by the Ollama model manifest, or {@code null}
     * if the provider did not supply this information.
     */
    public Long contextSize;

    /**
     * Cost tier label for external cloud models, e.g. {@code "low"}, {@code "medium"},
     * {@code "high"}.
     */
    public String externalCostTier;

    /** Whether this model is permitted to process sensitive content such as PII. */
    public boolean sensitiveAllowed;
}
