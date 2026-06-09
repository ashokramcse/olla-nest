package com.ollanest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight in-process event bus that dispatches named events to registered
 * handlers and persists every event to the {@code event_log} table for audit
 * purposes.
 *
 * <p>
 * Subscribers register a {@link java.util.function.BiConsumer} for a specific
 * event name (or {@code "*"} to receive all events). When an event fires, all
 * matching handlers are called asynchronously on a virtual-thread executor so
 * they cannot block the caller.
 *
 * <p>
 * Standard platform events include: {@code session.created},
 * {@code chat.message}, {@code chat.completed}, {@code email.received},
 * {@code email.sent}, {@code note.reminder}, {@code task.triggered},
 * {@code connector.synced}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Services need to react to each other's events (e.g. new email triggers an AI
 * triage, completed chat fires a memory-extraction pass) without direct
 * coupling. The event bus decouples producers from consumers and provides an
 * audit log as a side effect.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Subscriptions are stored in a {@code CopyOnWriteArrayList} — safe for
 * concurrent reads during event dispatch even if new subscribers register
 * mid-flight.</li>
 * <li>Handlers that throw are logged as warnings and do not affect other
 * subscribers or the calling thread.</li>
 * <li>Event persistence failures are logged but do not suppress handler
 * dispatch.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced for decoupled inter-service communication and
 * audit logging</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class EventBusService {

	private static final Logger log = LoggerFactory.getLogger(EventBusService.class);

	private record Subscription(String eventName, BiConsumer<String, Map<String, Object>> handler) {
	}

	/**
	 * Thread-safe subscription list; iterated during dispatch and written by
	 * subscribe calls.
	 */
	private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

	/** Virtual-thread executor that runs event handlers asynchronously. */
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** JDBC template for event_log persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for payload JSON serialisation. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialisation dependencies.
	 *
	 * @param db     JDBC template for the {@code event_log} table
	 * @param mapper shared Jackson mapper
	 * @since v2026.2.1
	 */
	public EventBusService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/**
	 * Registers a handler for the given event name.
	 *
	 * <p>
	 * The handler receives {@code (owner, payload)} where {@code owner} is the user
	 * who triggered the event (may be {@code null} for system events). Use
	 * {@code "*"} as {@code eventName} to receive all events regardless of name.
	 *
	 * @param eventName the event name to subscribe to, or {@code "*"} for all
	 *                  events
	 * @param handler   callback invoked with {@code (owner, payload)} when the
	 *                  event fires
	 * @since v2026.2.1
	 */
	public void subscribe(String eventName, BiConsumer<String, Map<String, Object>> handler) {
		subscriptions.add(new Subscription(eventName, handler));
	}

	/**
	 * Fire an event. Persists to audit log and notifies all matching subscribers
	 * asynchronously.
	 *
	 * @param eventName the event name (e.g. {@code "chat.completed"})
	 * @param owner     the user who triggered the event; may be {@code null} for
	 *                  system events
	 * @param payload   arbitrary event data map
	 * @since v2026.2.1
	 */
	public void fire(String eventName, String owner, Map<String, Object> payload) {
		persistEvent(eventName, owner, payload);

		List<Subscription> matches = new ArrayList<>();
		for (Subscription sub : subscriptions) {
			if ("*".equals(sub.eventName()) || sub.eventName().equals(eventName)) {
				matches.add(sub);
			}
		}

		for (Subscription sub : matches) {
			executor.submit(() -> {
				try {
					sub.handler().accept(owner, payload);
				} catch (Exception e) {
					log.warn("[event-bus] Handler error for event '{}': {}", eventName, e.getMessage());
				}
			});
		}
	}

	/**
	 * Convenience overload — fires an event with an empty payload map.
	 *
	 * @param eventName the event name
	 * @param owner     the user who triggered the event; may be {@code null}
	 * @since v2026.2.1
	 */
	public void fire(String eventName, String owner) {
		fire(eventName, owner, Map.of());
	}

	private void persistEvent(String eventName, String owner, Map<String, Object> payload) {
		try {
			String id = "ev-" + Long.toString(System.currentTimeMillis(), 36) + "-"
					+ UUID.randomUUID().toString().substring(0, 6);
			String payloadJson = mapper.writeValueAsString(payload);
			db.update("INSERT INTO event_log (id, event_name, owner, payload_json, created_at) VALUES (?,?,?,?,?)", id,
					eventName, owner, payloadJson, Instant.now().toString());
		} catch (Exception e) {
			log.warn("[event-bus] Failed to persist event '{}': {}", eventName, e.getMessage());
		}
	}
}
