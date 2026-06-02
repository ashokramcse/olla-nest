package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Lightweight in-process event bus for firing named events that trigger automation.
 *
 * Subscribers register a handler for a specific event name. When an event fires,
 * all matching handlers are called asynchronously on a virtual-thread executor.
 * Events are also persisted to the {@code event_log} table for audit purposes.
 *
 * Standard events:
 *   session.created, chat.message, chat.completed, email.received,
 *   note.reminder, task.triggered, connector.synced
 */
@Service
public class EventBusService {

    private static final Logger log = LoggerFactory.getLogger(EventBusService.class);

    private record Subscription(String eventName, BiConsumer<String, Map<String, Object>> handler) {}

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public EventBusService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * Subscribe to an event. The handler receives (owner, payload).
     * Use {@code "*"} as eventName to receive all events.
     */
    public void subscribe(String eventName, BiConsumer<String, Map<String, Object>> handler) {
        subscriptions.add(new Subscription(eventName, handler));
    }

    /**
     * Fire an event. Persists to audit log and notifies all matching subscribers asynchronously.
     *
     * @param eventName the event name (e.g. "chat.completed")
     * @param owner     the user who triggered the event; may be null for system events
     * @param payload   arbitrary event data
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

    /** Convenience overload — fire without payload. */
    public void fire(String eventName, String owner) {
        fire(eventName, owner, Map.of());
    }

    private void persistEvent(String eventName, String owner, Map<String, Object> payload) {
        try {
            String id = "ev-" + Long.toString(System.currentTimeMillis(), 36);
            String payloadJson = mapper.writeValueAsString(payload);
            db.update("INSERT INTO event_log (id, event_name, owner, payload_json, created_at) VALUES (?,?,?,?,?)",
                    id, eventName, owner, payloadJson, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[event-bus] Failed to persist event '{}': {}", eventName, e.getMessage());
        }
    }
}
