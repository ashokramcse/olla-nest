package com.ollanest.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Generic sliding-window in-memory rate limiter keyed by an arbitrary string (typically IP
 * address or user ID). Thread-safe. Periodic cleanup evicts stale entries automatically.
 *
 * Usage:
 * <pre>
 *   if (!rateLimiter.allow("login", ip, 5, 60)) {
 *       throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
 *   }
 * </pre>
 *
 * Each {@code bucket} (first parameter) is an independent sliding window — "login" and "chat"
 * have separate counters for the same key.
 */
@Service
public class RateLimiterService {

    // bucket:key -> list of request timestamps (epoch seconds)
    private final Map<String, List<Long>> log = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limiter-cleaner");
        t.setDaemon(true);
        return t;
    });

    public RateLimiterService() {
        cleaner.scheduleAtFixedRate(this::evictStale, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Returns {@code true} if the request is allowed; {@code false} if rate-limited.
     *
     * @param bucket      logical bucket name (e.g. "login", "chat", "search")
     * @param key         rate-limit key (e.g. IP address or user ID)
     * @param maxRequests maximum allowed requests in the window
     * @param windowSecs  sliding window duration in seconds
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

    /** Returns the number of requests in the current window for the given bucket+key. */
    public int count(String bucket, String key, int windowSecs) {
        String mapKey = bucket + ":" + key;
        long cutoff = Instant.now().getEpochSecond() - windowSecs;
        List<Long> timestamps = log.getOrDefault(mapKey, List.of());
        synchronized (this) {
            return (int) timestamps.stream().filter(t -> t > cutoff).count();
        }
    }

    /** Clears rate-limit state for a specific key across all buckets (e.g. on successful auth). */
    public void reset(String key) {
        log.keySet().removeIf(k -> k.endsWith(":" + key));
    }

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
