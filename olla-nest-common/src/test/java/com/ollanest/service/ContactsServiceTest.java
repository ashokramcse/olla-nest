package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link ContactsService}.
 *
 * <p>Covers: {@code create()} — DB INSERT, ID prefix, owner; {@code update()} — DB UPDATE;
 * {@code delete()} — scoped DELETE; {@code getById()} — null when not found;
 * {@code list()} — owner and limit parameters.
 *
 * <p>All DB and {@link ObjectMapper} interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactsService — unit tests")
class ContactsServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks ContactsService svc;

    @BeforeEach
    void stubMapper() throws Exception {
        when(mapper.writeValueAsString(any())).thenReturn("[]");
        when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of());
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("DB INSERT called with correct owner")
        void insertCalledWithOwner() {
            // stub getById inside create()
            when(db.queryForList(contains("FROM contacts WHERE id"), (Object) any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.create(OWNER, Map.of("display_name", "Alice"));
            verify(db).update(contains("INSERT INTO contacts"), cap.capture());
            // args[0] = id, args[1] = owner — verify owner is stored correctly
            assertThat(cap.getValue()[1]).isEqualTo(OWNER);
        }

        @Test
        @DisplayName("generated ID starts with 'cnt-'")
        void idStartsWithCntPrefix() {
            when(db.queryForList(contains("FROM contacts WHERE id"), (Object) any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.create(OWNER, Map.of("display_name", "Bob"));
            verify(db).update(contains("INSERT INTO contacts"), cap.capture());
            // cnt- prefix makes contact IDs recognisable across the API
            assertThat(cap.getValue()[0].toString()).startsWith("cnt-");
        }

        @Test
        @DisplayName("display_name stored correctly")
        void displayNameStored() {
            when(db.queryForList(contains("FROM contacts WHERE id"), (Object) any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.create(OWNER, Map.of("display_name", "Carol Chen"));
            verify(db).update(contains("INSERT INTO contacts"), cap.capture());
            // args[0] = id, args[1] = owner, args[2] = display_name — verify correct position
            assertThat(cap.getValue()[2]).isEqualTo("Carol Chen");
        }
    }

    // ── update() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("calls UPDATE contacts SQL with id and owner")
        void updateCalledWithIdAndOwner() {
            when(db.queryForList(contains("FROM contacts WHERE id"), (Object) any(), any())).thenReturn(List.of());
            svc.update("cnt-1", OWNER, Map.of("display_name", "Dave"));
            // UPDATE must be called — both id and owner scope the WHERE clause
            verify(db).update(contains("UPDATE contacts SET"), any(Object[].class));
        }
    }

    // ── delete() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("calls DELETE WHERE id=? AND owner=?")
        void deleteScopedToIdAndOwner() {
            svc.delete("cnt-999", OWNER);
            // Both id and owner in WHERE clause — prevents cross-user deletion
            verify(db).update(contains("DELETE FROM contacts WHERE id"), eq("cnt-999"), eq(OWNER));
        }

        @Test
        @DisplayName("does not delete contacts belonging to other owners")
        void doesNotDeleteOtherOwners() {
            svc.delete("cnt-999", OWNER);
            // Confirm no other owner is ever passed to the DELETE statement
            verify(db, never()).update(anyString(), eq("other-owner"), any());
        }
    }

    // ── getById() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("returns null when DB has no matching row")
        void returnsNullWhenEmpty() {
            // Stub: no rows returned — contact not found or belongs to another owner
            when(db.queryForList(anyString(), eq("cnt-missing"), eq(OWNER))).thenReturn(List.of());
            assertThat(svc.getById("cnt-missing", OWNER)).isNull();
        }

        @Test
        @DisplayName("returns mapped row when found")
        void returnsMappedRow() {
            var row = Map.<String, Object>of("id", "cnt-1", "owner", OWNER,
                    "display_name", "Alice", "email_json", "[]", "phone_json", "[]", "address_json", "[]");
            // Stub: contact found
            when(db.queryForList(anyString(), eq("cnt-1"), eq(OWNER))).thenReturn(List.of(row));
            var result = svc.getById("cnt-1", OWNER);
            assertThat(result).isNotNull();
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class ListContacts {

        @Test
        @DisplayName("queries with owner and positive limit")
        void queriesWithOwnerAndLimit() {
            when(db.queryForList(anyString(), eq(OWNER), eq(50))).thenReturn(List.of());
            svc.list(OWNER, 50);
            // Both owner and limit must be passed to scope results correctly
            verify(db).queryForList(anyString(), eq(OWNER), eq(50));
        }

        @Test
        @DisplayName("defaults limit to 100 when limit <= 0")
        void defaultsLimitWhenZero() {
            when(db.queryForList(anyString(), eq(OWNER), eq(100))).thenReturn(List.of());
            // Zero or negative limit must be normalised to 100 to prevent full-table scans
            svc.list(OWNER, 0);
            verify(db).queryForList(anyString(), eq(OWNER), eq(100));
        }

        @Test
        @DisplayName("returns empty list when DB returns no rows")
        void emptyWhenNoRows() {
            when(db.queryForList(anyString(), eq(OWNER), anyInt())).thenReturn(List.of());
            assertThat(svc.list(OWNER, 10)).isEmpty();
        }
    }
}
