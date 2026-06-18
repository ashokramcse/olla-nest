package com.ollanest.util;

import java.util.Map;

/**
 * Null-safe value coercion for request-body maps.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Java's {@link Map#getOrDefault(Object, Object)} only substitutes the default
 * when a key is <em>absent</em>. A JSON request body like
 * {@code {"title": null}} deserialises to a present key with a {@code null}
 * value, so {@code getOrDefault} returns {@code null} — which then reaches a
 * {@code NOT NULL} column and surfaces a raw {@code SQLITE_CONSTRAINT_NOTNULL}
 * as an HTTP 500 instead of being handled. This helper closes that gap so
 * controllers never persist an explicit JSON null into a non-null column.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@link #orDefault(Object, Object)} treats both the absent and the
 * explicit-null cases the same way, returning the fallback whenever the value
 * is {@code null}.</li>
 * <li>Use it for every column that is {@code NOT NULL} in the schema
 * (BUG-019 regression class).</li>
 * <li>Final class with a private constructor — a pure stateless utility, never
 * instantiated.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.2 — initial extraction to harden NOT-NULL request handling</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.2
 * @version v2026.2.2
 */
public final class MapDefaults {

	/**
	 * Private constructor — this is a stateless utility class and must not be
	 * instantiated.
	 *
	 * @since v2026.2.2
	 */
	private MapDefaults() {
	}

	/**
	 * Returns {@code value} when non-null, otherwise {@code fallback}.
	 *
	 * @param value    the candidate value (may be {@code null})
	 * @param fallback the default to use when {@code value} is {@code null}
	 * @return {@code value} if non-null, else {@code fallback}
	 * @since v2026.2.2
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
	 * @since v2026.2.2
	 */
	public static Object orDefault(Map<String, Object> map, String key, Object fallback) {
		return orDefault(map.get(key), fallback);
	}
}
