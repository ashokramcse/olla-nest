package com.ollanest.model;

import java.util.List;

/**
 * Parsed model descriptor — maps to the models table row.
 */
public class ModelRecord {
    public String id;
    public String name;
    public String provider;
    public String model;      // model_ref
    public String status;
    public List<String> capabilities;
    public int speedScore;
    public int qualityScore;
    public String privacy;
    public String lastSeenAt;
    public String governanceTier;
    public String resourceTier;
    public boolean gpuRequired;
    public int maxConcurrency;
    public long maxContextSize;
    public Long contextSize;
    public String externalCostTier;
    public boolean sensitiveAllowed;
}
