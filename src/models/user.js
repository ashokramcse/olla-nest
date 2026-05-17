/**
 * @file src/models/user.js
 * @description User model helpers: publicUser, getUsers, activeUser, userGroupIds, allowedModelIds,
 * allowedModels, effectiveAccess, departmentDefaults, userOverrides, roleCatalog, permissionCatalog.
 */

const { rows, one } = require("../db/index");
const { setting } = require("../db/settings");
const { safeJson, parseModel } = require("./model");

const USER_SELECT = `id, name, email, role, rights, department_id AS departmentId, active,
  employee_id AS employeeId, designation, team, branch, manager, organization,
  ai_access_tier AS aiAccessTier, daily_token_limit AS dailyTokenLimit,
  monthly_token_limit AS monthlyTokenLimit, gpu_quota_minutes AS gpuQuotaMinutes,
  vram_limit_mb AS vramLimitMb, concurrent_model_limit AS concurrentModelLimit,
  api_rate_limit_per_minute AS apiRateLimitPerMinute, max_context_size AS maxContextSize,
  mfa_enabled AS mfaEnabled, security_risk_score AS securityRiskScore,
  access_status AS accessStatus, access_expires_at AS accessExpiresAt, last_active_at AS lastActiveAt,
  auth_provider AS authProvider, phone, avatar_initials AS avatarInitials`;

/**
 * Maps a raw users table row (with camelCase column aliases) to the safe public
 * user shape sent to clients.  Strips password_hash and normalises types.
 * Returns null if given a falsy row so callers can check for 404 simply.
 *
 * @param {object|null} row - Raw SQLite row (may use snake_case or camelCase aliases).
 * @returns {object|null} Safe public user object.
 */
function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name,
    email: row.email,
    role: row.role,
    rights: safeJson(row.rights, []),
    departmentId: row.departmentId || row.department_id,
    active: row.active,
    employeeId: row.employeeId || row.employee_id || "",
    designation: row.designation || "",
    team: row.team || "",
    branch: row.branch || "",
    manager: row.manager || "",
    organization: row.organization || "Olla Nest",
    aiAccessTier: row.aiAccessTier || row.ai_access_tier || "standard",
    dailyTokenLimit: Number(row.dailyTokenLimit || row.daily_token_limit || 0),
    monthlyTokenLimit: Number(row.monthlyTokenLimit || row.monthly_token_limit || 0),
    gpuQuotaMinutes: Number(row.gpuQuotaMinutes || row.gpu_quota_minutes || 0),
    vramLimitMb: Number(row.vramLimitMb || row.vram_limit_mb || 0),
    concurrentModelLimit: Number(row.concurrentModelLimit || row.concurrent_model_limit || 0),
    apiRateLimitPerMinute: Number(row.apiRateLimitPerMinute || row.api_rate_limit_per_minute || 0),
    maxContextSize: Number(row.maxContextSize || row.max_context_size || 0),
    mfaEnabled: Boolean(row.mfaEnabled || row.mfa_enabled),
    securityRiskScore: Number(row.securityRiskScore || row.security_risk_score || 0),
    accessStatus: row.accessStatus || row.access_status || (row.active ? "active" : "suspended"),
    accessExpiresAt: row.accessExpiresAt || row.access_expires_at || "",
    lastActiveAt: row.lastActiveAt || row.last_active_at || "",
    authProvider: row.authProvider || row.auth_provider || "local",
    phone: row.phone || "",
    avatarInitials: row.avatarInitials || row.avatar_initials || "",
    isEnterprise: (row.authProvider || row.auth_provider || "local") !== "local",
  };
}

function getUsers(db) {
  return rows(db, `SELECT ${USER_SELECT} FROM users ORDER BY role, name`).map(publicUser);
}

function activeUser(db) {
  const id = setting(db, "activeUserId", "u-admin");
  return publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, id) || one(db, `SELECT ${USER_SELECT} FROM users LIMIT 1`));
}

function userGroupIds(db, userId) {
  return rows(db, "SELECT group_id AS id FROM user_groups WHERE user_id = ?", userId).map((row) => row.id);
}

function roleCatalog(db) {
  return rows(db, "SELECT id, name, description, permissions, system_role AS systemRole FROM role_catalog ORDER BY name").map((role) => ({
    ...role,
    permissions: safeJson(role.permissions, []),
    systemRole: Boolean(role.systemRole),
  }));
}

function permissionCatalog(db) {
  return rows(db, "SELECT key, category, description, risk_level AS riskLevel FROM permission_catalog ORDER BY category, key");
}

function departmentDefaults(departmentId) {
  const defaults = {
    "dept-product": ["chat:use", "models:local:use", "models:coding:use", "workspace:build", "files:upload"],
    "dept-support": ["chat:use", "models:local:use", "models:reasoning:use", "files:upload"],
    "dept-general": ["chat:use", "models:local:use"],
  };
  return defaults[departmentId] || defaults["dept-general"];
}

function userOverrides(db, userId) {
  return rows(db, "SELECT id, user_id AS userId, permission_key AS permissionKey, model_id AS modelId, effect, reason, expires_at AS expiresAt, created_at AS createdAt FROM user_overrides WHERE user_id = ? ORDER BY created_at DESC", userId);
}

/**
 * Computes a user's effective permission set by merging:
 *   1. Explicit rights stored on the user row
 *   2. Department default rights (from deptDefaultRights setting)
 *   3. Role catalog permissions (if user.role matches a role_catalog id)
 *   4. Per-user overrides (allow/deny, respecting expiry times)
 *
 * Deny overrides take final precedence — they are removed from the merged set last.
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @returns {{ permissions: string[], denied: string[], allowedModelIds: string[],
 *             groups: string[], sourcePriority: string[], quotas: object }}
 */
function effectiveAccess(db, user) {
  const role = one(db, "SELECT permissions FROM role_catalog WHERE id = ?", user.role) || null;
  const permissions = new Set([...(user.rights || []), ...departmentDefaults(user.departmentId), ...safeJson(role?.permissions, [])]);
  const denied = new Set();
  for (const override of userOverrides(db, user.id)) {
    if (override.expiresAt && new Date(override.expiresAt).getTime() < Date.now()) continue;
    if (override.effect === "deny") denied.add(override.permissionKey);
    if (override.effect === "allow") permissions.add(override.permissionKey);
  }
  denied.forEach((permission) => permissions.delete(permission));
  return {
    permissions: Array.from(permissions).sort(),
    denied: Array.from(denied).sort(),
    allowedModelIds: allowedModelIds(db, user),
    groups: userGroupIds(db, user.id),
    sourcePriority: ["user override", "department policy", "role permission", "organization default"],
    quotas: {
      dailyTokenLimit: user.dailyTokenLimit,
      monthlyTokenLimit: user.monthlyTokenLimit,
      gpuQuotaMinutes: user.gpuQuotaMinutes,
      vramLimitMb: user.vramLimitMb,
      concurrentModelLimit: user.concurrentModelLimit,
      apiRateLimitPerMinute: user.apiRateLimitPerMinute,
      maxContextSize: user.maxContextSize,
    },
  };
}

/**
 * Returns the list of model IDs a user is allowed to use.
 *
 * Resolution order:
 *  1. Admins: all non-disabled models.
 *  2. access_grants rows matching user / department / any group the user belongs to.
 *  3. Implicit grants: if user has models:local:use right, grant all available non-API models;
 *     if user has models:external:use, also grant API models.  This avoids requiring an admin
 *     to create explicit grants for every new model when users already have the broad permission.
 *  4. user_overrides: allow/deny individual model IDs (respecting expiry).
 *
 * @param {DatabaseSync} db
 * @param {object} user - publicUser() shaped object.
 * @returns {string[]} Array of allowed model IDs.
 */
function allowedModelIds(db, user) {
  if (user.role === "admin") {
    return rows(db, "SELECT id FROM models WHERE status != 'disabled'").map((row) => row.id);
  }
  const groupIds = userGroupIds(db, user.id);
  const subjects = [
    ["user", user.id],
    ["department", user.departmentId],
    ...groupIds.map((id) => ["group", id]),
  ];
  const ids = new Set();
  const stmt = db.prepare("SELECT model_id FROM access_grants WHERE subject_type = ? AND subject_id = ? AND can_use = 1");
  for (const [type, id] of subjects) {
    stmt.all(type, id).forEach((row) => ids.add(row.model_id));
  }

  // If user has models:local:use (or models:manage) via their rights OR department defaults,
  // grant access to all available local (non-API) models.
  // This ensures newly created users don't get "No approved active model" just because
  // no explicit access_grants rows exist for them yet.
  const effectiveRights = new Set([
    ...(user.rights || []),
    ...departmentDefaults(user.departmentId),
  ]);
  if (effectiveRights.has("models:local:use") || effectiveRights.has("models:manage") || effectiveRights.has("models:external:use")) {
    rows(db, "SELECT id FROM models WHERE provider != 'api' AND status != 'disabled'")
      .forEach(row => ids.add(row.id));
  }
  if (effectiveRights.has("models:external:use") || effectiveRights.has("api:use")) {
    rows(db, "SELECT id FROM models WHERE provider = 'api' AND status != 'disabled'")
      .forEach(row => ids.add(row.id));
  }

  for (const override of userOverrides(db, user.id)) {
    if (!override.modelId) continue;
    if (override.expiresAt && new Date(override.expiresAt).getTime() < Date.now()) continue;
    if (override.effect === "allow") ids.add(override.modelId);
    if (override.effect === "deny") ids.delete(override.modelId);
  }
  return Array.from(ids);
}

function allowedModels(db, user) {
  const ids = new Set(allowedModelIds(db, user));
  const allowApi = setting(db, "allowApiModels", false);
  return rows(db, "SELECT * FROM models WHERE status = 'available' OR status = 'configured'")
    .map(parseModel)
    .filter((model) => ids.has(model.id) && (model.provider !== "api" || allowApi));
}

module.exports = {
  USER_SELECT,
  publicUser,
  getUsers,
  activeUser,
  userGroupIds,
  allowedModelIds,
  allowedModels,
  effectiveAccess,
  departmentDefaults,
  userOverrides,
  roleCatalog,
  permissionCatalog,
};
