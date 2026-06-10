package com.ollanest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * Generic in-memory sliding-window rate limiter keyed by an arbitrary string,
 * typically an IP address or user ID.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Login endpoints, chat completions, and search requests all need independent
 * rate limits that survive across multiple requests without a distributed
 * cache. This service provides a lightweight, thread-safe sliding-window
 * counter with named buckets so a single instance can enforce different
 * policies for {@code "login"}, {@code "chat"}, {@code "search"}, etc., without
 * any per-bucket configuration.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All state is held in a {@link java.util.concurrent.ConcurrentHashMap};
 * the map is accessed under a coarse {@code synchronized} block to keep the
 * timestamp-list mutations atomic.</li>
 * <li>A daemon background thread evicts entries whose timestamps are all older
 * than one hour, capping memory use over long uptime periods.</li>
 * <li>The {@link #reset} method is intended for use on successful
 * authentication to immediately lift the login-attempt block for that key.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced alongside the in-process auth hardening pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class RateLimiterService {

	/** Per-bucket-key timestamp log; key format is {@code bucket:key}. */
	private final Map<String, List<Long>> log = new ConcurrentHashMap<>();

	/** Background daemon that evicts stale timestamp entries every 5 minutes. */
	private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rate-limiter-cleaner");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Constructs the rate limiter and schedules the background cleanup task.
	 *
	 * @since v2026.2.1
	 */
	public RateLimiterService() {
		cleaner.scheduleAtFixedRate(this::evictStale, 5, 5, TimeUnit.MINUTES);
	}

	/**
	 * Returns {@code true} if the request is allowed; {@code false} if
	 * rate-limited.
	 *
	 * @param bucket      logical bucket name (e.g. "login", "chat", "search")
	 * @param key         rate-limit key (e.g. IP address or user ID)
	 * @param maxRequests maximum allowed requests in the window
	 * @param windowSecs  sliding window duration in seconds
	 * @return {@code true} if the request is within the limit; {@code false} if
	 *         rate-limited
	 * @since v2026.2.1
	 */
	public boolean allow(String bucket, String key, int maxRequests, int windowSecs) {
		String mapKey = bucket + ":" + key;
		long now = Instant.now().getEpochSecond();
		long cutoff = now - windowSecs;

		synchronized (this) {
			List<Long> timestamps = log.computeIfAbsent(mapKey, k -> new ArrayList<>());
			timestamps.removeIf(t -> t <= cutoff);
			if (timestamps.size() >= maxRequests) {
				return false;
			}
			timestamps.add(now);
			return true;
		}
	}

	/**
	 * Returns the number of requests recorded in the current window for the given
	 * bucket and key.
	 *
	 * @param bucket     logical bucket name
	 * @param key        rate-limit key
	 * @param windowSecs sliding window duration in seconds
	 * @return the count of requests within the window
	 * @since v2026.2.1
	 */
	public int count(String bucket, String key, int windowSecs) {
		String mapKey = bucket + ":" + key;
		long cutoff = Instant.now().getEpochSecond() - windowSecs;
		List<Long> timestamps = log.getOrDefault(mapKey, List.of());
		synchronized (this) {
			return (int) timestamps.stream().filter(t -> t > cutoff).count();
		}
	}

	/**
	 * Clears all rate-limit state for a specific key across every bucket. Typically
	 * called on successful authentication to immediately lift any active
	 * login-attempt block.
	 *
	 * @param key the rate-limit key to reset (e.g. IP address or user ID)
	 * @since v2026.2.1
	 */
	public void reset(String key) {
		log.keySet().removeIf(k -> k.endsWith(":" + key));
	}

	/**
	 * Removes expired entries from the in-memory rate-limit window map to bound its
	 * size, called opportunistically before recording a new attempt.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	private void evictStale() {
		long cutoff = Instant.now().getEpochSecond() - 3600; // keep 1h max
		synchronized (this) {
			log.entrySet().removeIf(e -> {
				e.getValue().removeIf(t -> t <= cutoff);
				return e.getValue().isEmpty();
			});
		}
	}
}
