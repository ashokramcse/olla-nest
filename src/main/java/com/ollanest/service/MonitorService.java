package com.ollanest.service;

import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process telemetry counter used by the health and metrics endpoints.
 *
 * <h3>Why this class exists</h3>
 * <p>Provides lightweight, zero-dependency request and error counters together with
 * JVM memory statistics for the {@code GET /api/health} endpoint. This avoids pulling
 * in a full metrics library (Micrometer/Prometheus) for a single internal health check
 * while still giving operators the data they need to confirm the process is alive and
 * to catch error spikes.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>All counters use {@link AtomicLong} for thread safety without synchronization
 *       overhead.</li>
 *   <li>Uptime is calculated from {@code System.currentTimeMillis()} captured at
 *       construction time rather than from {@link ManagementFactory#getRuntimeMXBean()}
 *       so that the reported uptime reflects application-context startup, not JVM
 *       startup.</li>
 *   <li>The import of {@link ManagementFactory} is retained for potential future use;
 *       it is not invoked at runtime.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 *   <li>v2026.1.0 — initial migration; replaces Node.js in-memory counter object</li>
 *   <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * </ul>
 *
 * @author  Ashok Ram
 * @since   v2026.1.0
 * @version v2026.1.4
 */
@Service
public class MonitorService {

    /** Total number of requests processed since application startup. */
    private final AtomicLong requestCount = new AtomicLong(0);

    /** Total number of requests that resulted in an error since application startup. */
    private final AtomicLong errorCount = new AtomicLong(0);

    /** Epoch millisecond at which this service was instantiated (proxy for app start time). */
    private final long startMs = System.currentTimeMillis();

    /**
     * Increments the total request counter by one.
     *
     * <p>Should be called once per inbound API request, typically from a servlet
     * filter or controller advice.
     *
     * @since  v2026.1.0
     */
    public void incrementRequests() { requestCount.incrementAndGet(); }

    /**
     * Increments the error counter by one.
     *
     * <p>Should be called whenever a request results in a 4xx or 5xx response.
     *
     * @since  v2026.1.0
     */
    public void incrementErrors() { errorCount.incrementAndGet(); }

    /**
     * Returns a point-in-time snapshot of key runtime metrics.
     *
     * <p>The returned map contains:
     * <ul>
     *   <li>{@code uptimeMs} — milliseconds since service instantiation</li>
     *   <li>{@code requests} — cumulative request count</li>
     *   <li>{@code errors} — cumulative error count</li>
     *   <li>{@code memoryUsedMb} — heap bytes currently in use, converted to MiB</li>
     *   <li>{@code memoryTotalMb} — JVM maximum heap size in MiB</li>
     * </ul>
     *
     * @return an ordered {@link Map} of metric names to their current values
     * @since  v2026.1.0
     */
    public Map<String, Object> getSnapshot() {
        long uptimeMs = System.currentTimeMillis() - startMs;
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("uptimeMs", uptimeMs);
        snap.put("requests", requestCount.get());
        snap.put("errors", errorCount.get());
        snap.put("memoryUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        snap.put("memoryTotalMb", runtime.maxMemory() / (1024 * 1024));
        return snap;
    }
}
