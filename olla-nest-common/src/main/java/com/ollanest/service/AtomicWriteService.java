package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-safe file writes using the write-to-temp + fsync + atomic rename pattern.
 *
 * A plain FileWriter truncates the target file before writing new content — a
 * process kill between truncation and completion leaves a zero-byte or partial
 * file. This service writes to a PID-suffixed sibling temp file, fsyncs the
 * file data and its parent directory, then atomically renames it into place.
 * On POSIX (Linux/macOS) {@code Files.move(ATOMIC_MOVE)} is a single syscall;
 * on Windows it falls back to a non-atomic replace which is still safer than
 * overwriting in place.
 */
@Service
public class AtomicWriteService {

    private static final Logger log = LoggerFactory.getLogger(AtomicWriteService.class);
    private final ObjectMapper mapper;

    public AtomicWriteService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Atomically write {@code data} as pretty-printed JSON to {@code path}. */
    public void writeJson(Path path, Object data) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        writeText(path, json);
    }

    /** Atomically write {@code text} (UTF-8) to {@code path}. */
    public void writeText(Path path, String text) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException e) {
            // ATOMIC_MOVE not supported on this FS (e.g. some Windows network shares)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw e;
        }
    }

    /** Atomically write raw bytes to {@code path}. */
    public void writeBytes(Path path, byte[] bytes) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw e;
        }
    }
}
