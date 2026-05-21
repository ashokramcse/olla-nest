package com.ollanest.model;

import java.util.List;

/**
 * Public user object — safe to send to clients (no password_hash).
 */
public class User {
    public String id;
    public String name;
    public String email;
    public String role;
    public List<String> rights;
    public String departmentId;
    public boolean active;
    public String employeeId;
    public String designation;
    public String team;
    public String branch;
    public String manager;
    public String organization;
    public String aiAccessTier;
    public long dailyTokenLimit;
    public long monthlyTokenLimit;
    public long gpuQuotaMinutes;
    public long vramLimitMb;
    public long concurrentModelLimit;
    public long apiRateLimitPerMinute;
    public long maxContextSize;
    public boolean mfaEnabled;
    public long securityRiskScore;
    public String accessStatus;
    public String accessExpiresAt;
    public String lastActiveAt;
    public String authProvider;
    public String phone;
    public String avatarInitials;
    public boolean isEnterprise;
}
