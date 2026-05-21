package com.ollanest.service;

import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request counters for health endpoint.
 */
@Service
public class MonitorService {

    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final long startMs = System.currentTimeMillis();

    public void incrementRequests() { requestCount.incrementAndGet(); }
    public void incrementErrors() { errorCount.incrementAndGet(); }

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
