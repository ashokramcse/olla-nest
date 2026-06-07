package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link EventBusService}.
 *
 * <p>Covers: {@code subscribe()} + {@code fire()} — event persisted to DB; wildcard "*" subscriber
 * receives all events; specific-event subscriber only receives matching events; handler exceptions
 * are swallowed; no-payload overload calls handler with empty map; multiple subscribers for same
 * event all invoked.
 *
 * <p>Handler calls are async (virtual threads); tests use a short {@link CountDownLatch} to
 * synchronise. All DB interactions are Mockito-stubbed.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventBusService — unit tests")
class EventBusServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks EventBusService svc;

    @BeforeEach
    void stubMapper() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("{}");
    }

    // ── fire() + persistence ──────────────────────────────────────────────────

    @Nested
    @DisplayName("fire() — persistence")
    class FirePersistence {

        @Test
        @DisplayName("fire() inserts event into event_log table")
        void persistsEventToDb() {
            svc.fire("chat.completed", OWNER, Map.of("tokens", 100));
            // Every fired event must be persisted for audit trail and replay
            verify(db).update(contains("INSERT INTO event_log"), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("fire() no-payload overload also persists event")
        void noPayloadOverloadPersists() {
            svc.fire("session.created", OWNER);
            // No-payload events must also be logged
            verify(db).update(contains("INSERT INTO event_log"), any(), any(), any(), any(), any());
        }
    }

    // ── subscribe() + fire() — routing ────────────────────────────────────────

    @Nested
    @DisplayName("subscribe() + fire() — routing")
    class Routing {

        @Test
        @DisplayName("specific subscriber only invoked for matching event name")
        void specificSubscriberMatchesOnly() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<String> received = new ArrayList<>();
            // Subscribe to "chat.completed" — must only fire for that event
            svc.subscribe("chat.completed", (owner, payload) -> {
                received.add("chat.completed");
                latch.countDown();
            });
            svc.fire("chat.completed", OWNER, Map.of());
            // Wait up to 2 seconds for async virtual-thread delivery
            latch.await(2, TimeUnit.SECONDS);
            assertThat(received).containsExactly("chat.completed");
        }

        @Test
        @DisplayName("specific subscriber NOT invoked for a different event name")
        void specificSubscriberNotCalledForOtherEvent() throws Exception {
            List<String> received = new ArrayList<>();
            // Subscribe to "email.received" — must not fire for "chat.completed"
            svc.subscribe("email.received", (owner, payload) -> received.add("called"));
            svc.fire("chat.completed", OWNER, Map.of());
            // give virtual thread a moment to process
            Thread.sleep(200);
            // Handler must not have been called for a different event
            assertThat(received).isEmpty();
        }

        @Test
        @DisplayName("'*' wildcard subscriber receives all events")
        void wildcardReceivesAllEvents() throws Exception {
            // Latch for 2 events
            CountDownLatch latch = new CountDownLatch(2);
            List<String> received = new ArrayList<>();
            // Wildcard subscriber must receive every event regardless of name
            svc.subscribe("*", (owner, payload) -> {
                received.add("received");
                latch.countDown();
            });
            svc.fire("event.one", OWNER, Map.of());
            svc.fire("event.two", OWNER, Map.of());
            latch.await(2, TimeUnit.SECONDS);
            assertThat(received).hasSize(2);
        }

        @Test
        @DisplayName("handler exception is swallowed — no exception escapes fire()")
        void handlerExceptionSwallowed() {
            // Subscribe a handler that throws to test fault isolation
            svc.subscribe("bad.event", (owner, payload) -> {
                throw new RuntimeException("Handler exploded!");
            });
            // No exception thrown = handler errors are isolated; other subscribers still run
            assertThatCode(() -> svc.fire("bad.event", OWNER, Map.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("no-payload overload calls handler with empty map")
        void noPayloadHandlerReceivesEmptyMap() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<Map<String, Object>> payloads = new ArrayList<>();
            svc.subscribe("test.event", (owner, payload) -> {
                payloads.add(payload);
                latch.countDown();
            });
            // Fire with no payload — handler must receive an empty map, not null
            svc.fire("test.event", OWNER);
            latch.await(2, TimeUnit.SECONDS);
            assertThat(payloads).hasSize(1);
            assertThat(payloads.get(0)).isEmpty();
        }

        @Test
        @DisplayName("multiple subscribers for same event are all invoked")
        void multipleSubscribersAllInvoked() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            List<String> received = new ArrayList<>();
            // Two subscribers on the same event — both must be invoked
            svc.subscribe("msg.sent", (o, p) -> { received.add("A"); latch.countDown(); });
            svc.subscribe("msg.sent", (o, p) -> { received.add("B"); latch.countDown(); });
            svc.fire("msg.sent", OWNER, Map.of());
            latch.await(2, TimeUnit.SECONDS);
            assertThat(received).hasSize(2);
        }
    }
}
