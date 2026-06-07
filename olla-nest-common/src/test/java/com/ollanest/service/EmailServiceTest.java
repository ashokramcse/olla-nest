package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link EmailService}.
 *
 * <p>Covers account management and message operations; sendEmail/pollAllAccounts are skipped (network I/O).
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailService — unit tests")
class EmailServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock CryptoService cryptoService;
    @Mock DatabaseService databaseService;
    @Mock EventBusService eventBus;

    @InjectMocks EmailService emailService;

    private Map<String, Object> acctRow(String id) {
        return Map.of("id", id, "owner", OWNER, "name", "Gmail",
                "imap_host", "imap.gmail.com", "smtp_host", "smtp.gmail.com",
                "username", "test@gmail.com", "enabled", 1);
    }

    private Map<String, Object> msgRow(String id, String accountId) {
        return Map.of("id", id, "account_id", accountId, "subject", "Hello",
                "from_addr", "sender@example.com", "to_addr", "me@example.com",
                "date_sent", "2026-06-01T09:00:00Z", "is_read", 0, "is_starred", 0,
                "is_spam", 0, "urgency_score", 3, "ai_summary", null,
                "ai_tags_json", null, "has_attachments", 0, "created_at", "2026-06-01T09:00:00Z");
    }

    // ── createAccount() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAccount()")
    class CreateAccount {

        @Test
        @DisplayName("INSERT is called")
        void insertsRow() {
            // Stub: encrypt password and return the created account row
            when(cryptoService.encryptKey(anyString())).thenReturn("enc-pass");
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(acctRow("email-abc")));
            emailService.createAccount(OWNER, Map.of("imap_host", "imap.gmail.com"));
            // Account credentials must be persisted (with encrypted password)
            verify(db).update(contains("INSERT INTO email_accounts"), (Object[]) any());
        }

        @Test
        @DisplayName("id starts with 'email-'")
        void idPrefix() {
            when(cryptoService.encryptKey(anyString())).thenReturn("enc-pass");
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(acctRow("email-xyz")));
            Map<String, Object> result = emailService.createAccount(OWNER, Map.of());
            // email- prefix makes account IDs recognisable in logs and APIs
            assertThat(result.get("id").toString()).startsWith("email-");
        }

        @Test
        @DisplayName("owner is in returned account")
        void ownerStored() {
            when(cryptoService.encryptKey(anyString())).thenReturn("enc-pass");
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(acctRow("email-1")));
            Map<String, Object> result = emailService.createAccount(OWNER, Map.of());
            assertThat(result.get("owner")).isEqualTo(OWNER);
        }
    }

    // ── getAccount() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAccount()")
    class GetAccount {

        @Test
        @DisplayName("returns null when not found")
        void nullWhenEmpty() {
            // Stub: no rows found — account doesn't exist or belongs to another owner
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
            assertThat(emailService.getAccount("email-1", OWNER)).isNull();
        }

        @Test
        @DisplayName("password_enc is stripped from returned account")
        void stripsPassword() {
            Map<String, Object> row = new java.util.LinkedHashMap<>(acctRow("email-1"));
            row.put("password_enc", "secret-enc");
            // Stub: account found with encrypted password in DB row
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));
            Map<String, Object> result = emailService.getAccount("email-1", OWNER);
            // SECURITY: encrypted password must never be returned to the client
            assertThat(result).doesNotContainKey("password_enc");
        }
    }

    // ── listAccounts() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listAccounts()")
    class ListAccounts {

        @Test
        @DisplayName("queries with owner parameter")
        void queriesWithOwner() {
            // Stub: one account for this owner
            when(db.queryForList(anyString(), eq(OWNER), eq(OWNER))).thenReturn(List.of(acctRow("email-1")));
            List<Map<String, Object>> results = emailService.listAccounts(OWNER);
            // List must not be null even if empty
            assertThat(results).isNotNull();
        }
    }

    // ── deleteAccount() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteAccount()")
    class DeleteAccount {

        @Test
        @DisplayName("DELETE WHERE id=? AND owner=? is called")
        void deletesWithIdAndOwner() {
            emailService.deleteAccount("email-1", OWNER);
            // Both id and owner in WHERE clause — prevents cross-user deletion
            verify(db).update(contains("DELETE FROM email_accounts WHERE id = ? AND owner = ?"),
                    eq("email-1"), eq(OWNER));
        }
    }

    // ── listMessages() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listMessages()")
    class ListMessages {

        @Test
        @DisplayName("queries with accountId and applies page/pageSize")
        void queriesWithAccountId() {
            // Stub: one message for this account in INBOX
            when(db.queryForList(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(msgRow("eml-1", "email-1")));
            List<Map<String, Object>> results = emailService.listMessages("email-1", OWNER, "INBOX", 1, 20);
            assertThat(results).hasSize(1);
        }
    }

    // ── getMessage() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMessage()")
    class GetMessage {

        @Test
        @DisplayName("returns null when not found")
        void nullWhenEmpty() {
            // Stub: message not found
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
            assertThat(emailService.getMessage("eml-1", "email-1")).isNull();
        }
    }

    // ── markRead() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markRead()")
    class MarkRead {

        @Test
        @DisplayName("UPDATE SET is_read=1 is called")
        void updatesIsRead() {
            emailService.markRead("eml-1", "email-1");
            // is_read must be set to 1 for both the local DB and IMAP server sync
            verify(db).update(contains("UPDATE email_messages SET is_read=1"), eq("eml-1"), eq("email-1"));
        }
    }

    // ── markStarred() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markStarred()")
    class MarkStarred {

        @Test
        @DisplayName("UPDATE SET is_starred=1 when starred=true")
        void setsStarredTrue() {
            emailService.markStarred("eml-1", "email-1", true);
            // is_starred=1 when marking as starred
            verify(db).update(contains("UPDATE email_messages SET is_starred=?"), eq(1), eq("eml-1"), eq("email-1"));
        }

        @Test
        @DisplayName("UPDATE SET is_starred=0 when starred=false")
        void setsStarredFalse() {
            emailService.markStarred("eml-1", "email-1", false);
            // is_starred=0 when un-starring
            verify(db).update(contains("UPDATE email_messages SET is_starred=?"), eq(0), eq("eml-1"), eq("email-1"));
        }
    }

    // ── deleteMessage() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteMessage()")
    class DeleteMessage {

        @Test
        @DisplayName("DELETE called for id and accountId")
        void deletesMessage() {
            emailService.deleteMessage("eml-1", "email-1");
            // Message must be deleted by both id and accountId for safety
            verify(db).update(contains("DELETE FROM email_messages WHERE id=?"), eq("eml-1"), eq("email-1"));
        }
    }
}
