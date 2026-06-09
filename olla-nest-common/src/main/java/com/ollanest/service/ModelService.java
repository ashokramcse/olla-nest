package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ollanest.model.User;

import java.util.*;

/**
 * Model data-access and resolution service.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The {@code models} table stores raw JDBC rows with JSON-encoded capability
 * arrays, nullable numeric columns, and provider-specific conventions. Every
 * other service that needs a strongly-typed {@link ModelRecord} must go through
 * this class, which owns the mapping logic and the access-control filter. This
 * mirrors the helper functions in {@code src/models/model.js}
 * ({@code parseModel}) and {@code src/models/user.js} ({@code allowedModels})
 * from the prior Node.js implementation.
 *
 * <h3>Design notes</h3>
 * <p>
 * {@link #parseModel(Map)} is deliberately permissive — missing or null columns
 * produce safe default values so that partial rows (e.g. from model imports) do
 * not crash the router. The {@code context_size} and {@code max_context_size}
 * columns are treated as aliases; whichever is non-zero wins.
 * <p>
 * {@link #allowedModels(com.ollanest.model.User)} applies two independent
 * filters: the user's access-grant set (from
 * {@link UserService#allowedModelIds}) and the global {@code allowApiModels}
 * feature flag. API-provider models are excluded when the flag is {@code false}
 * regardless of individual grants.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@Service
public class ModelService {

	/** Spring JDBC template for all database access. */
	private final JdbcTemplate db;

	/** Jackson mapper for deserialising JSON-encoded capability arrays. */
	private final ObjectMapper mapper;

	/**
	 * Service that resolves which model IDs a specific user is permitted to access.
	 */
	private final UserService userService;

	/**
	 * Service for reading the {@code allowApiModels} feature flag and other
	 * settings.
	 */
	private final DatabaseService databaseService;

	/**
	 * Constructs the service with its required collaborators.
	 *
	 * @param db              Spring JDBC template bound to the application's data
	 *                        source
	 * @param mapper          shared Jackson {@link ObjectMapper}
	 * @param userService     user access-grant resolution service
	 * @param databaseService key-value settings service
	 * @since v2026.1.0
	 */
	public ModelService(JdbcTemplate db, ObjectMapper mapper, UserService userService,
			DatabaseService databaseService) {
		this.db = db;
		this.mapper = mapper;
		this.userService = userService;
		this.databaseService = databaseService;
	}

	/**
	 * Maps a raw JDBC result row to a typed {@link ModelRecord}.
	 *
	 * <p>
	 * All field reads are null-safe; missing columns produce sensible defaults
	 * ({@code 0} for scores, {@code false} for booleans, empty list for
	 * capabilities). The {@code context_size} and {@code max_context_size} columns
	 * are coalesced into a single value; a zero result is stored as {@code null} to
	 * signal "unknown".
	 *
	 * @param row a raw row map from
	 *            {@code db.queryForList("SELECT * FROM models …")}
	 * @return a fully populated {@link ModelRecord}; never {@code null}
	 * @since v2026.1.0
	 */
	public ModelRecord parseModel(Map<String, Object> row) {
		ModelRecord m = new ModelRecord();
		m.id = str(row, "id");
		m.name = str(row, "name");
		m.provider = str(row, "provider");
		m.model = str(row, "model_ref");
		m.status = str(row, "status");
		m.capabilities = safeJsonList(str(row, "capabilities"));
		m.speedScore = intVal(row, 0, "speed_score");
		m.qualityScore = intVal(row, 0, "quality_score");
		m.privacy = str(row, "privacy");
		m.lastSeenAt = str(row, "last_seen_at");
		m.governanceTier = strOr(row, "approved-local", "governance_tier");
		m.resourceTier = strOr(row, "standard", "resource_tier");
		m.gpuRequired = boolVal(row, "gpu_required");
		m.maxConcurrency = intVal(row, 2, "max_concurrency");
		long ctxSize = longVal(row, 0, "context_size", "max_context_size");
		m.contextSize = ctxSize > 0 ? ctxSize : null;
		m.maxContextSize = ctxSize;
		m.externalCostTier = strOr(row, "local-free", "external_cost_tier");
		Object sa = row.get("sensitive_allowed");
		m.sensitiveAllowed = sa == null || ((Number) sa).intValue() != 0;
		return m;
	}

	/**
	 * Returns all models in the database, ordered by provider then name.
	 *
	 * <p>
	 * Includes models in every status (available, missing, offline, disabled). Use
	 * {@link #allowedModels(com.ollanest.model.User)} to get only the models a
	 * specific user may chat with.
	 *
	 * @return list of all {@link ModelRecord} instances; empty list when the table
	 *         is empty
	 * @since v2026.1.0
	 */
	public List<ModelRecord> getAllModels() {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM models ORDER BY provider, name");
		List<ModelRecord> result = new ArrayList<>();
		for (Map<String, Object> row : rows)
			result.add(parseModel(row));
		return result;
	}

	/**
	 * Returns the subset of available models that the given user is permitted to
	 * use.
	 *
	 * <p>
	 * Applies two independent filters:
	 * <ol>
	 * <li>The user's access-grant set, resolved by
	 * {@link UserService#allowedModelIds}.</li>
	 * <li>The global {@code allowApiModels} feature flag — models with
	 * {@code provider = "api"} are excluded when the flag is {@code false}.</li>
	 * </ol>
	 * Only models with {@code status = 'available'} or
	 * {@code status = 'configured'} are considered; offline, missing, or disabled
	 * models are always excluded.
	 *
	 * @param user the authenticated user whose access grants are evaluated
	 * @return list of models the user may use, in the order returned by the DB
	 *         query
	 * @since v2026.1.0
	 */
	public List<ModelRecord> allowedModels(User user) {
		List<String> ids = new HashSet<>(userService.allowedModelIds(user)).stream().toList();
		boolean allowApi = databaseService.getSettingBool("allowApiModels", false);

		List<Map<String, Object>> rows = db
				.queryForList("SELECT * FROM models WHERE status = 'available' OR status = 'configured'");
		List<ModelRecord> result = new ArrayList<>();
		Set<String> idSet = new HashSet<>(ids);
		for (Map<String, Object> row : rows) {
			ModelRecord m = parseModel(row);
			if (idSet.contains(m.id) && (!"api".equals(m.provider) || allowApi)) {
				result.add(m);
			}
		}
		return result;
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/**
	 * Safely deserialises a JSON array string into a list of strings.
	 *
	 * <p>
	 * Returns an empty list for {@code null}, blank, or unparseable input.
	 *
	 * @param json JSON array string; {@code null} or blank returns an empty list
	 * @return the deserialised list, or an empty {@link ArrayList} on any error
	 * @since v2026.1.0
	 */
	public List<String> safeJsonList(String json) {
		try {
			if (json == null || json.isBlank())
				return new ArrayList<>();
			return mapper.readValue(json, new TypeReference<List<String>>() {
			});
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	/**
	 * Returns the string value of a row column, or {@code null} if absent.
	 *
	 * @param row JDBC result row map
	 * @param key column name
	 * @return string representation of the value, or {@code null}
	 */
	private String str(Map<String, Object> row, String key) {
		Object v = row.get(key);
		return v != null ? v.toString() : null;
	}

	/**
	 * Returns the string value of the first non-null column from a list of
	 * candidates, falling back to {@code def} when all are null.
	 *
	 * @param row  JDBC result row map
	 * @param def  default value when all candidate keys are missing or null
	 * @param keys one or more column names to try in order
	 * @return the first non-null string value found, or {@code def}
	 */
	private String strOr(Map<String, Object> row, String def, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null)
				return v.toString();
		}
		return def;
	}

	/**
	 * Returns the boolean interpretation of a row column.
	 *
	 * <p>
	 * Accepts {@link Boolean}, {@link Number} (non-zero = true), and string
	 * ({@code "1"} or {@code "true"}). Returns {@code false} when the column is
	 * absent.
	 *
	 * @param row JDBC result row map
	 * @param key column name
	 * @return boolean value of the column, or {@code false} if absent
	 */
	private boolean boolVal(Map<String, Object> row, String key) {
		Object v = row.get(key);
		if (v == null)
			return false;
		if (v instanceof Boolean)
			return (Boolean) v;
		if (v instanceof Number)
			return ((Number) v).intValue() != 0;
		return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
	}

	/**
	 * Returns the integer value of the first non-null column from a list of
	 * candidates, falling back to {@code def}.
	 *
	 * @param row  JDBC result row map
	 * @param def  default value when all candidate keys are missing
	 * @param keys one or more column names to try in order
	 * @return the first non-null integer value, or {@code def}
	 */
	private int intVal(Map<String, Object> row, int def, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null) {
				try {
					return ((Number) v).intValue();
				} catch (Exception ignored) {
				}
			}
		}
		return def;
	}

	/**
	 * Returns the long value of the first non-null column from a list of
	 * candidates, falling back to {@code def}.
	 *
	 * @param row  JDBC result row map
	 * @param def  default value when all candidate keys are missing
	 * @param keys one or more column names to try in order
	 * @return the first non-null long value, or {@code def}
	 */
	private long longVal(Map<String, Object> row, long def, String... keys) {
		for (String k : keys) {
			Object v = row.get(k);
			if (v != null) {
				try {
					return ((Number) v).longValue();
				} catch (Exception ignored) {
				}
			}
		}
		return def;
	}
}
