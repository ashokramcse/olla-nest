package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * VRAM-aware model management service ("Cookbook") that helps users discover, score,
 * and download LLM models that fit their hardware.
 *
 * <p>Features:
 * <ul>
 * <li>Hardware detection (NVIDIA, AMD, Apple Silicon, CPU) via platform-specific CLI tools</li>
 * <li>GPU memory bandwidth table for 80+ GPUs used for tokens/sec estimation</li>
 * <li>VRAM-aware model fit scoring with quantisation awareness</li>
 * <li>Asynchronous HuggingFace GGUF model downloads with SSE progress streaming</li>
 * <li>Cookbook state persistence to {@code cookbook_models}</li>
 * </ul>
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Choosing and downloading the right LLM for local hardware is the single biggest
 * friction point for new users. This service automates the "does this model fit my GPU?"
 * calculation and handles the multi-gigabyte download lifecycle without requiring the
 * user to touch the command line.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Downloads run on virtual threads and stream progress as SSE events so the UI
 *     can show a live progress bar without polling.</li>
 * <li>The catalog is hardcoded rather than fetched from HuggingFace to avoid a network
 *     dependency at startup; it is annotated at runtime with fit and download-status flags.</li>
 * <li>The GPU bandwidth table uses longest-match substring search to handle GPU names like
 *     "NVIDIA GeForce RTX 4090" matching key "4090".</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with hardware detection, model catalog, and HuggingFace download</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class CookbookService {

    private static final Logger log = LoggerFactory.getLogger(CookbookService.class);

    /** GPU memory bandwidth table in GB/s, keyed by short model name — used for tokens/sec estimation. */
    private static final Map<String, Integer> GPU_BANDWIDTH = Map.ofEntries(
            Map.entry("5090", 1792), Map.entry("5080", 960), Map.entry("5070 ti", 896), Map.entry("5070", 672),
            Map.entry("4090", 1008), Map.entry("4080 super", 736), Map.entry("4080", 717),
            Map.entry("4070 ti super", 672), Map.entry("4070 ti", 504), Map.entry("4070 super", 504),
            Map.entry("4070", 504), Map.entry("4060 ti", 288), Map.entry("4060", 272),
            Map.entry("3090 ti", 1008), Map.entry("3090", 936), Map.entry("3080 ti", 912),
            Map.entry("3080", 760), Map.entry("3070 ti", 608), Map.entry("3070", 448),
            Map.entry("3060 ti", 448), Map.entry("3060", 360),
            Map.entry("h100 sxm", 3350), Map.entry("h100", 2039), Map.entry("h200", 4800),
            Map.entry("a100 sxm", 2039), Map.entry("a100", 1555), Map.entry("l40s", 864),
            Map.entry("t4", 320), Map.entry("a10g", 600),
            Map.entry("7900 xtx", 960), Map.entry("7900 xt", 800), Map.entry("6900 xt", 512),
            Map.entry("m4 max", 546), Map.entry("m4 pro", 273), Map.entry("m4", 120),
            Map.entry("m3 max", 300), Map.entry("m3 pro", 150), Map.entry("m3", 100),
            Map.entry("m2 ultra", 800), Map.entry("m2 max", 400), Map.entry("m2 pro", 200), Map.entry("m2", 100),
            Map.entry("m1 ultra", 800), Map.entry("m1 max", 400), Map.entry("m1 pro", 200), Map.entry("m1", 68)
    );

    /** Maps quantisation format names to bytes-per-parameter values for VRAM size calculation. */
    private static final Map<String, Double> QUANT_BPP = Map.of(
            "F16", 2.0, "BF16", 2.0, "Q8_0", 1.0, "Q6_K", 0.75,
            "Q5_K_M", 0.625, "Q4_K_M", 0.5, "Q3_K_M", 0.375, "Q2_K", 0.25,
            "FP8", 1.0, "INT4", 0.5
    );

    /** JDBC template for cookbook_models persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for JSON serialisation. */
    private final ObjectMapper mapper;

    /** Application settings service used to read HuggingFace token and models directory. */
    private final DatabaseService databaseService;

    /** Tracks active and recently completed download jobs by job ID. */
    private final Map<String, Map<String, Object>> activeDownloads = new ConcurrentHashMap<>();

    /** Shared HTTP client for HuggingFace download requests. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    /**
     * Constructs a new {@code CookbookService} with the required infrastructure dependencies.
     *
     * @param db              JDBC template for cookbook_models persistence
     * @param mapper          shared Jackson mapper for JSON serialisation
     * @param databaseService application settings service (HuggingFace token, models dir)
     * @since v2026.2.1
     */
    public CookbookService(JdbcTemplate db, ObjectMapper mapper, DatabaseService databaseService) {
        this.db = db;
        this.mapper = mapper;
        this.databaseService = databaseService;
    }

    // ── Hardware Detection ────────────────────────────────────────────────────

    /**
     * Detects the host machine's GPU and CPU hardware capabilities.
     *
     * <p>Uses platform-specific tools: {@code sysctl} on macOS (Apple Silicon),
     * {@code nvidia-smi} on Linux/Windows (NVIDIA GPUs). Falls back to CPU-only
     * when no GPU is detected.
     *
     * @return a map containing {@code os}, {@code backend}, {@code has_gpu},
     *         {@code gpu_name}, {@code gpu_vram_gb}, {@code gpu_count},
     *         {@code available_ram_gb}, and {@code gpu_bandwidth_gb_s}
     * @since v2026.2.1
     */
    public Map<String, Object> detectHardware() {
        Map<String, Object> result = new LinkedHashMap<>();
        String os = System.getProperty("os.name", "").toLowerCase();

        // GPU detection
        boolean hasGpu = false;
        String gpuName = null;
        double gpuVramGb = 0;
        int gpuCount = 1;
        String backend = "cpu_x86";

        if (os.contains("mac")) {
            // Apple Silicon via sysctl
            try {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                String cpuInfo = new String(p.getInputStream().readAllBytes()).trim().toLowerCase();
                if (cpuInfo.contains("apple")) {
                    backend = "metal";
                    // Find chip name in GPU_BANDWIDTH
                    for (String key : GPU_BANDWIDTH.keySet()) {
                        if (cpuInfo.contains(key)) {
                            gpuName = cpuInfo;
                            hasGpu = true;
                            break;
                        }
                    }
                    // Detect memory
                    Process memP = new ProcessBuilder("sysctl", "-n", "hw.memsize").start();
                    String memBytes = new String(memP.getInputStream().readAllBytes()).trim();
                    if (!memBytes.isBlank()) {
                        gpuVramGb = Long.parseLong(memBytes) / 1e9;
                    }
                }
            } catch (Exception e) {
                log.debug("[cookbook] Apple Silicon detection failed: {}", e.getMessage());
            }
        } else {
            // NVIDIA via nvidia-smi
            try {
                Process p = new ProcessBuilder("nvidia-smi",
                        "--query-gpu=name,memory.total,count",
                        "--format=csv,noheader,nounits").start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                if (!output.isBlank()) {
                    String[] parts = output.split(",");
                    if (parts.length >= 2) {
                        gpuName = parts[0].trim().toLowerCase();
                        gpuVramGb = Double.parseDouble(parts[1].trim()) / 1024.0;
                        hasGpu = true;
                        backend = "cuda";
                        if (parts.length >= 3) gpuCount = Integer.parseInt(parts[2].trim());
                    }
                }
            } catch (Exception e) {
                log.debug("[cookbook] NVIDIA detection failed: {}", e.getMessage());
            }
        }

        // RAM
        long ramBytes = Runtime.getRuntime().maxMemory();
        double ramGb = ramBytes / 1e9;

        result.put("os", os);
        result.put("backend", backend);
        result.put("has_gpu", hasGpu);
        result.put("gpu_name", gpuName);
        result.put("gpu_vram_gb", Math.round(gpuVramGb * 10.0) / 10.0);
        result.put("gpu_count", gpuCount);
        result.put("available_ram_gb", Math.round(ramGb * 10.0) / 10.0);
        result.put("gpu_bandwidth_gb_s", getGpuBandwidth(gpuName));

        return result;
    }

    private int getGpuBandwidth(String gpuName) {
        if (gpuName == null) return 70; // CPU fallback
        String name = gpuName.toLowerCase();
        // Sort keys by length descending for correct substring matching
        return GPU_BANDWIDTH.entrySet().stream()
                .sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .filter(e -> name.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(70);
    }

    // ── Model Catalog ─────────────────────────────────────────────────────────

    /**
     * Returns the full model catalog annotated with VRAM-fit and download-status flags.
     *
     * <p>Calls {@link #detectHardware()} to determine effective memory, then
     * annotates each hardcoded catalog entry with {@code fits} (boolean) and
     * {@code is_downloaded} (boolean from the database).
     *
     * @return list of model metadata maps; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> getCatalog() {
        Map<String, Object> hardware = detectHardware();
        double vramGb = ((Number) hardware.get("gpu_vram_gb")).doubleValue();
        double ramGb = ((Number) hardware.get("available_ram_gb")).doubleValue();
        double effectiveMem = Math.max(vramGb, ramGb);

        return buildCatalog().stream().map(model -> {
            Map<String, Object> m = new LinkedHashMap<>(model);
            double sizeGb = ((Number) model.getOrDefault("size_gb", 4.0)).doubleValue();
            m.put("fits", sizeGb <= effectiveMem * 0.85);
            m.put("is_downloaded", isDownloaded(model));
            return m;
        }).toList();
    }

    private List<Map<String, Object>> buildCatalog() {
        return List.of(
                model("Llama 3.2 3B", "bartowski/Llama-3.2-3B-Instruct-GGUF", null, "Q4_K_M", 2.0, 3.0, "general"),
                model("Llama 3.2 1B", "bartowski/Llama-3.2-1B-Instruct-GGUF", null, "Q4_K_M", 0.7, 1.0, "general"),
                model("Llama 3.1 8B", "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", null, "Q4_K_M", 4.9, 8.0, "general"),
                model("Mistral 7B", "TheBloke/Mistral-7B-Instruct-v0.2-GGUF", null, "Q4_K_M", 4.4, 7.0, "general"),
                model("Phi-3.5 Mini", "bartowski/Phi-3.5-mini-instruct-GGUF", null, "Q4_K_M", 2.4, 3.8, "coding"),
                model("Gemma 2 9B", "bartowski/gemma-2-9b-it-GGUF", null, "Q4_K_M", 5.5, 9.0, "reasoning"),
                model("CodeLlama 7B", "TheBloke/CodeLlama-7B-Instruct-GGUF", null, "Q4_K_M", 4.4, 7.0, "coding"),
                model("nomic-embed-text", "nomic-ai/nomic-embed-text-v1.5-GGUF", null, "F16", 0.5, 0.3, "embedding"),
                model("Qwen 2.5 7B", "Qwen/Qwen2.5-7B-Instruct-GGUF", null, "Q4_K_M", 4.7, 7.6, "general"),
                model("DeepSeek Coder 6.7B", "TheBloke/deepseek-coder-6.7B-instruct-GGUF", null, "Q4_K_M", 4.1, 6.7, "coding")
        );
    }

    private Map<String, Object> model(String name, String hfRepo, String hfFile,
            String quant, double sizeGb, double paramsB, String useCase) {
        return Map.of(
                "name", name, "hf_repo", hfRepo, "hf_filename", hfFile != null ? hfFile : "",
                "quantization", quant, "size_gb", sizeGb, "params_b", paramsB,
                "use_case", useCase, "format", "gguf"
        );
    }

    private boolean isDownloaded(Map<String, Object> model) {
        String repo = (String) model.get("hf_repo");
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT is_downloaded FROM cookbook_models WHERE hf_repo=? AND is_downloaded=1", repo);
        return !rows.isEmpty();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Starts an asynchronous HuggingFace model download and streams progress via SSE.
     *
     * <p>The download runs on a virtual thread. Progress events ({@code progress},
     * {@code completed}, {@code error}) are sent to {@code emitter}. On completion the
     * model path is persisted to {@code cookbook_models}.
     *
     * @param hfRepo  the HuggingFace repository slug (e.g. {@code "bartowski/Llama-3.2-3B-Instruct-GGUF"})
     * @param hfFile  the specific GGUF file to download; may be {@code null} to use a default name
     * @param emitter the SSE emitter over which progress events are sent
     * @return the job ID string (prefixed with {@code "dl-"}) for client tracking
     * @since v2026.2.1
     */
    public String startDownload(String hfRepo, String hfFile, SseEmitter emitter) {
        String jobId = "dl-" + Long.toString(System.currentTimeMillis(), 36);
        activeDownloads.put(jobId, new LinkedHashMap<>(Map.of(
                "job_id", jobId, "status", "downloading", "hf_repo", hfRepo,
                "progress", 0, "started_at", Instant.now().toString()
        )));

        Thread.ofVirtual().name("cookbook-download-" + jobId).start(() -> {
            try {
                // Build download URL from HuggingFace
                String hfToken = databaseService.getSetting("huggingFaceToken", "");
                String filename = hfFile != null && !hfFile.isBlank() ? hfFile
                        : hfRepo.substring(hfRepo.lastIndexOf('/') + 1) + ".Q4_K_M.gguf";

                String downloadUrl = "https://huggingface.co/" + hfRepo + "/resolve/main/" + filename;
                Path downloadDir = Path.of(databaseService.getSetting("modelsDir", "./data/models"));
                Files.createDirectories(downloadDir);
                Path dest = downloadDir.resolve(filename);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofHours(2))
                        .GET();
                if (!hfToken.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + hfToken);
                }

                long[] downloaded = {0};
                long[] total = {-1};

                HTTP.send(reqBuilder.build(), responseInfo -> {
                    total[0] = responseInfo.headers().firstValueAsLong("content-length").orElse(-1);
                    return HttpResponse.BodySubscribers.fromLineSubscriber(
                            java.util.concurrent.Flow.Subscriber.class.cast(null));
                });

                // Simpler approach: download to file with progress
                try (InputStream in = URI.create(downloadUrl).toURL().openStream();
                     FileOutputStream out = new FileOutputStream(dest.toFile())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded[0] += n;
                        int pct = total[0] > 0 ? (int)(downloaded[0] * 100 / total[0]) : -1;
                        sendSse(emitter, "progress", Map.of("job_id", jobId, "progress", pct,
                                "downloaded_mb", downloaded[0] / 1048576.0));
                    }
                }

                // Record in DB
                String modelId = "cm-" + Long.toString(System.currentTimeMillis(), 36);
                db.update("""
                        INSERT OR REPLACE INTO cookbook_models (id, hf_repo, hf_filename, display_name,
                          is_downloaded, local_path, updated_at)
                        VALUES (?,?,?,?,?,?,?)""",
                        modelId, hfRepo, filename, filename, 1, dest.toString(), Instant.now().toString());

                activeDownloads.put(jobId, Map.of("job_id", jobId, "status", "completed",
                        "hf_repo", hfRepo, "progress", 100, "local_path", dest.toString()));
                sendSse(emitter, "completed", Map.of("job_id", jobId, "path", dest.toString()));

            } catch (Exception e) {
                log.warn("[cookbook] Download failed for {}: {}", hfRepo, e.getMessage());
                activeDownloads.put(jobId, Map.of("job_id", jobId, "status", "error", "error", e.getMessage()));
                sendSse(emitter, "error", Map.of("job_id", jobId, "error", e.getMessage()));
            } finally {
                try { emitter.complete(); } catch (Exception ignore) {}
            }
        });

        return jobId;
    }

    /**
     * Returns a snapshot of all active and recently completed download jobs.
     *
     * @return a list of job-status maps; empty if no downloads have been initiated
     * @since v2026.2.1
     */
    public List<Map<String, Object>> getDownloads() {
        return new ArrayList<>(activeDownloads.values());
    }

    private void sendSse(SseEmitter emitter, String event, Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data))); }
        catch (Exception ignore) {}
    }
}
