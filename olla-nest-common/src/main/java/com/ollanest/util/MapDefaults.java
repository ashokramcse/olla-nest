package com.ollanest.util;

import java.util.Map;

/**
 * Null-safe value coercion for request-body maps.
 *
 * <p>
 * Java's {@link Map#getOrDefault(Object, Object)} only substitutes the default
 * when a key is <em>absent</em>. A JSON request body like
 * {@code {"title": null}} deserialises to a present key with a {@code null}
 * value, so {@code getOrDefault} returns {@code null} — which then reaches a
 * {@code NOT NULL} column and surfaces a raw {@code SQLITE_CONSTRAINT_NOTNULL}
 * as an HTTP 500 instead of being handled.
 *
 * <p>
 * {@link #orDefault(Object, Object)} treats both the absent and the
 * explicit-null cases the same way, returning the fallback whenever the value
 * is {@code null}. Use it for every column that is {@code NOT NULL} in the
 * schema (BUG-019).
 *
 * @author Ashok Ram
 * @since v2026.2.2
 * @version v2026.2.2
 */
public final class MapDefaults {

	private MapDefaults() {
	}

	/**
	 * Returns {@code value} when non-null, otherwise {@code fallback}.
	 *
	 * @param value    the candidate value (may be {@code null})
	 * @param fallback the default to use when {@code value} is {@code null}
	 * @return {@code value} if non-null, else {@code fallback}
	 */
	public static Object orDefault(Object value, Object fallback) {
		return value != null ? value : fallback;
	}

	/**
	 * Convenience overload reading a key from a map and coercing its value.
	 *
	 * @param map      the source map
	 * @param key      the key to read
	 * @param fallback the default when the key is absent or maps to {@code null}
	 * @return the non-null value or {@code fallback}
	 */
	public static Object orDefault(Map<String, Object> map, String key, Object fallback) {
		return orDefault(map.get(key), fallback);
	}
}
