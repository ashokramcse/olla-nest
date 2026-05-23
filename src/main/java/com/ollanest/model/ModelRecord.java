package com.ollanest.model;

import java.util.List;

/**
 * Immutable POJO representing a model row from the {@code models} table.
 *
 * <p>Fields map directly to DB columns and are populated by
 * {@code ModelService.parseModel()} from a JDBC result-set Map. Used throughout
 * the Router, Provider, and Chat layers to carry model metadata without repeated
 * DB lookups.
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li>Plain public fields (no getters/setters) to keep deserialization simple
 *       and avoid Jackson configuration overhead.</li>
 *   <li>{@code contextSize} is a boxed {@code Long} so it can be {@code null}
 *       when the Ollama manifest does not report a context window.</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0  — initial Java Spring Boot migration
 */
public class ModelRecord {

    /** Unique model identifier (UUID primary key from the DB). */
    public String id;

    /** Human-readable display name shown in the UI. */
    public String name;

    /** Provider key, e.g. {@code "ollama"}, {@code "anthropic"}, {@code "openai"}. */
    public String provider;

    /** Model reference string passed to the provider API, e.g. {@code "llama3:8b"}. */
    public String model;

    /** Lifecycle status: {@code "available"}, {@code "disabled"}, {@code "pending"}. */
    public String status;

    /** Capability tags used by the router, e.g. {@code ["code","reasoning"]}. */
    public List<String> capabilities;

    /** Router speed score (0–100); higher means faster expected response. */
    public int speedScore;

    /** Router quality score (0–100); higher means better expected output quality. */
    public int qualityScore;

    /**
     * Privacy classification: {@code "local"} (on-prem Ollama) or
     * {@code "external"} (cloud provider). The router uses this to enforce the
     * privacy gate when sensitive content is detected.
     */
    public String privacy;

    /** ISO-8601 timestamp of the last time this model was seen alive by ModelService. */
    public String lastSeenAt;

    /** Governance tier controlling which users may access this model. */
    public String governanceTier;

    /** Resource tier for quota accounting (e.g. {@code "standard"}, {@code "gpu"}). */
    public String resourceTier;

    /** Whether this model requires a GPU to run; used for scheduling decisions. */
    public boolean gpuRequired;

    /** Maximum simultaneous requests allowed for this model. */
    public int maxConcurrency;

    /** Hard maximum context size in tokens enforced by governance policy. */
    public long maxContextSize;

    /**
     * Actual context window reported by the Ollama manifest, or {@code null}
     * if the provider did not supply this information.
     */
    public Long contextSize;

    /** Cost tier label for external models, e.g. {@code "low"}, {@code "high"}. */
    public String externalCostTier;

    /** Whether this model is permitted to process sensitive content (PII, etc.). */
    public boolean sensitiveAllowed;
}
