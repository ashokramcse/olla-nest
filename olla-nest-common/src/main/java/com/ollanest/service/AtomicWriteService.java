package com.ollanest.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Provides crash-safe file writes using the write-to-temp + fsync + atomic
 * rename pattern.
 *
 * <p>
 * A plain {@code FileWriter} truncates the target file before writing new
 * content — a process kill between truncation and completion leaves a zero-byte
 * or partial file. This service writes to a PID-suffixed sibling temp file,
 * fsyncs the file data, then atomically renames it into place. On POSIX
 * (Linux/macOS) {@code Files.move(ATOMIC_MOVE)} is a single syscall; on Windows
 * it falls back to a non-atomic replace which is still safer than overwriting
 * in place.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Several services persist state to disk (settings, exported files, gallery
 * images). Centralising the safe-write pattern here ensures every write site
 * gets fsync and atomic rename without copy-pasting the try/finally cleanup
 * logic.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The temp file carries the current PID suffix so concurrent JVM instances
 * (e.g. rolling restart) never collide.</li>
 * <li>Parent directory creation is idempotent — the method is safe to call even
 * if the target path does not exist yet.</li>
 * <li>{@link java.nio.file.StandardOpenOption#SYNC} ensures kernel buffers are
 * flushed before the rename, which matters for SQLite WAL and GGUF model
 * writes.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced for crash-safe persistence across gallery,
 * settings, and export services</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class AtomicWriteService {


	/** Shared Jackson mapper used for pretty-printing JSON output. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects the Jackson mapper.
	 *
	 * @param mapper shared Jackson mapper for JSON serialisation
	 * @since v2026.2.1
	 */
	public AtomicWriteService(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Atomically writes {@code data} as pretty-printed JSON to {@code path}.
	 *
	 * @param path the destination file path
	 * @param data the object to serialise; must be Jackson-serialisable
	 * @throws IOException if the write or rename fails
	 * @since v2026.2.1
	 */
	public void writeJson(Path path, Object data) throws IOException {
		String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
		writeText(path, json);
	}

	/**
	 * Atomically writes {@code text} as UTF-8 to {@code path}.
	 *
	 * @param path the destination file path
	 * @param text the UTF-8 string to write
	 * @throws IOException if the write or rename fails
	 * @since v2026.2.1
	 */
	public void writeText(Path path, String text) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);

		Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + ProcessHandle.current().pid());
		try {
			Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (UnsupportedOperationException e) {
			// ATOMIC_MOVE not supported on this FS (e.g. some Windows network shares)
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignore) {
			}
			throw e;
		}
	}

	/**
	 * Atomically writes raw bytes to {@code path}.
	 *
	 * @param path  the destination file path
	 * @param bytes the byte array to write
	 * @throws IOException if the write or rename fails
	 * @since v2026.2.1
	 */
	public void writeBytes(Path path, byte[] bytes) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);

		Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + ProcessHandle.current().pid());
		try {
			Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.SYNC);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (UnsupportedOperationException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignore) {
			}
			throw e;
		}
	}
}
