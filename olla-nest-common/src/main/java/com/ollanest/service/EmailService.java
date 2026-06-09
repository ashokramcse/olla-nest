package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import jakarta.mail.Message;
import java.util.UUID;

/**
 * Full-featured email service providing IMAP inbox management, SMTP sending, and
 * AI-powered triage for multiple accounts per user.
 *
 * <p>Supports personal and team-shared inboxes. AI triage assigns urgency scores (1–5),
 * auto-tags, summarises, and detects spam via the configured LLM. Background polling
 * runs every {@value #DEFAULT_POLL_INTERVAL_S} seconds per account on a virtual thread.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Email is the primary communication channel for most users. Hosting email data locally
 * and running AI triage on-device means no email content ever leaves the user's
 * infrastructure. The service integrates with any standard IMAP/SMTP server.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each account's poll runs on a separate virtual thread to avoid head-of-line
 *     blocking when one server is slow.</li>
 * <li>AI triage is always asynchronous — it never blocks message import or delivery.</li>
 * <li>Passwords are stored encrypted via {@link CryptoService}; they are never exposed
 *     in API responses ({@code password_enc} is stripped in {@link #getAccount}).</li>
 * <li>Thread grouping uses the {@code In-Reply-To} / {@code References} headers from
 *     RFC 2822, resolving to an existing local thread ID where possible.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with multi-account IMAP/SMTP, AI triage, reply drafts,
 *     and background polling</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final int DEFAULT_POLL_INTERVAL_S = 300;
    private static final int FETCH_BATCH = 50;

    /** JDBC template for email account and message persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for tags JSON serialisation. */
    private final ObjectMapper mapper;

    /** Encrypts and decrypts IMAP/SMTP passwords stored in the database. */
    private final CryptoService cryptoService;

    /** Application settings service used to read LLM endpoint and model name. */
    private final DatabaseService databaseService;

    /** Event bus used to fire {@code email.received} and {@code email.sent} events. */
    private final EventBusService eventBus;

    /** Maps account IDs to their last successful poll epoch-second timestamp. */
    private final Map<String, Long> lastPoll = new ConcurrentHashMap<>();

    /** Shared HTTP client for LLM triage calls. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * Constructor-injects all required service dependencies.
     *
     * @param db              JDBC template for email tables
     * @param mapper          shared Jackson mapper
     * @param cryptoService   service for encrypting email credentials
     * @param databaseService application settings service
     * @param eventBus        event bus for email lifecycle events
     * @since v2026.2.1
     */
    public EmailService(JdbcTemplate db, ObjectMapper mapper, CryptoService cryptoService,
            DatabaseService databaseService, EventBusService eventBus) {
        this.db = db;
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.databaseService = databaseService;
        this.eventBus = eventBus;
    }

    // ── Account Management ────────────────────────────────────────────────────

    /**
     * Creates a new email account configuration for the given owner.
     *
     * @param owner the user ID who owns this account
     * @param req   account fields: {@code imap_host}, {@code smtp_host}, {@code username},
     *              {@code password}, {@code name}, {@code display_name}, {@code signature}, etc.
     * @return the created account row (password_enc stripped); never null
     * @since v2026.2.1
     */
    /** Null-safe String coercion for JSON request values. */
    private static String asString(Object o) {
        return o == null ? null : (o instanceof String s ? s : o.toString());
    }

    public Map<String, Object> createAccount(String owner, Map<String, Object> req) {
        // Validate the NOT NULL columns that have no DB default, so a malformed
        // request returns a clean 400 (IllegalArgumentException -> BAD_REQUEST)
        // instead of leaking a SQLITE_CONSTRAINT_NOTNULL as a 500.
        String imapHost = asString(req.get("imap_host"));
        String smtpHost = asString(req.get("smtp_host"));
        String username = asString(req.get("username"));
        if (imapHost == null || imapHost.isBlank())
            throw new IllegalArgumentException("imap_host is required");
        if (smtpHost == null || smtpHost.isBlank())
            throw new IllegalArgumentException("smtp_host is required");
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("username (email address) is required");

        // Random-suffixed id so rapid creates in the same millisecond cannot collide.
        String id = "email-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        String password = (String) req.get("password");
        String encPassword = cryptoService.encryptKey(password != null ? password : "");
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO email_accounts (id, owner, name, imap_host, imap_port, imap_security,
                  smtp_host, smtp_port, smtp_security, username, password_enc, display_name,
                  signature, poll_interval_s, enabled, team_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("name", "Email"),
                imapHost,
                req.getOrDefault("imap_port", 993),
                req.getOrDefault("imap_security", "SSL"),
                smtpHost,
                req.getOrDefault("smtp_port", 587),
                req.getOrDefault("smtp_security", "STARTTLS"),
                username,
                encPassword,
                req.getOrDefault("display_name", ""),
                req.getOrDefault("signature", ""),
                req.getOrDefault("poll_interval_s", DEFAULT_POLL_INTERVAL_S),
                1,
                req.get("team_id"),
                now, now);

        return getAccount(id, owner);
    }

    /**
     * Returns the email account with the given ID visible to the owner, or {@code null} if not found.
     * {@code password_enc} is always stripped from the returned map.
     *
     * @param id    the account ID
     * @param owner the requesting user ID
     * @return the account row map without password, or {@code null}
     * @since v2026.2.1
     */
    public Map<String, Object> getAccount(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM email_accounts WHERE id = ? AND (owner = ? OR team_id IS NOT NULL)", id, owner);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.remove("password_enc"); // never expose password
        return row;
    }

    /**
     * Returns all email accounts owned by or shared with the given user.
     *
     * @param owner the user ID
     * @return list of account row maps (without passwords); never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listAccounts(String owner) {
        return db.queryForList(
                "SELECT id, owner, name, imap_host, smtp_host, username, display_name, enabled, last_polled_at, team_id FROM email_accounts WHERE owner = ? OR team_id IN (SELECT team FROM users WHERE id = ?)",
                owner, owner)
                .stream().map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)).toList();
    }

    /**
     * Deletes the email account with the given ID, restricted to the owning user.
     *
     * @param id    the account ID to delete
     * @param owner the user ID — only the owner may delete
     * @since v2026.2.1
     */
    public void deleteAccount(String id, String owner) {
        db.update("DELETE FROM email_accounts WHERE id = ? AND owner = ?", id, owner);
    }

    // ── IMAP: List Messages ────────────────────────────────────────────────────

    /**
     * Returns a paginated list of email messages for the given account and folder.
     *
     * @param accountId the email account ID
     * @param owner     the requesting user ID
     * @param folder    the IMAP folder name (e.g. {@code "INBOX"})
     * @param page      1-based page number
     * @param pageSize  number of messages per page
     * @return list of message row maps ordered by date descending; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listMessages(String accountId, String owner, String folder, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return db.queryForList("""
                SELECT id, account_id, subject, from_addr, to_addr, date_sent, is_read,
                       is_starred, is_spam, urgency_score, ai_summary, ai_tags_json,
                       has_attachments, created_at
                FROM email_messages
                WHERE account_id = ? AND folder = ?
                ORDER BY date_sent DESC LIMIT ? OFFSET ?""",
                accountId, folder, pageSize, offset)
                .stream().map(this::mapMessageRow).toList();
    }

    /**
     * Returns a single email message by ID, marking it read as a side effect.
     *
     * @param id        the message ID
     * @param accountId the account the message belongs to
     * @return the message row map, or {@code null} if not found
     * @since v2026.2.1
     */
    public Map<String, Object> getMessage(String id, String accountId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM email_messages WHERE id = ? AND account_id = ?", id, accountId);
        if (rows.isEmpty()) return null;
        markRead(id, accountId);
        return mapMessageRow(rows.get(0));
    }

    /**
     * Returns all messages in a given thread, ordered chronologically.
     *
     * @param threadId  the thread ID (derived from Message-ID / In-Reply-To headers)
     * @param accountId the account the thread belongs to
     * @return ordered list of message row maps; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> getThread(String threadId, String accountId) {
        return db.queryForList(
                "SELECT * FROM email_messages WHERE thread_id = ? AND account_id = ? ORDER BY date_sent ASC",
                threadId, accountId)
                .stream().map(this::mapMessageRow).toList();
    }

    /**
     * Marks the given message as read.
     *
     * @param id        the message ID
     * @param accountId the account the message belongs to
     * @since v2026.2.1
     */
    public void markRead(String id, String accountId) {
        db.update("UPDATE email_messages SET is_read=1 WHERE id=? AND account_id=?", id, accountId);
    }

    /**
     * Sets the starred flag on the given message.
     *
     * @param id        the message ID
     * @param accountId the account the message belongs to
     * @param starred   {@code true} to star, {@code false} to unstar
     * @since v2026.2.1
     */
    public void markStarred(String id, String accountId, boolean starred) {
        db.update("UPDATE email_messages SET is_starred=? WHERE id=? AND account_id=?",
                starred ? 1 : 0, id, accountId);
    }

    /**
     * Permanently deletes the given message from the local store.
     *
     * @param id        the message ID to delete
     * @param accountId the account the message belongs to
     * @since v2026.2.1
     */
    public void deleteMessage(String id, String accountId) {
        db.update("DELETE FROM email_messages WHERE id=? AND account_id=?", id, accountId);
    }

    // ── SMTP: Send ────────────────────────────────────────────────────────────

    /**
     * Sends an email via SMTP using the specified account configuration.
     *
     * <p>Decrypts the stored IMAP/SMTP password, builds a MIME multipart message with
     * optional display name and signature, and delivers it via {@code jakarta.mail.Transport}.
     * Fires an {@code email.sent} event after successful delivery.
     *
     * @param accountId the email account ID to send from
     * @param owner     the requesting user ID (must own or share the account)
     * @param req       send fields: {@code to}, {@code cc}, {@code subject}, {@code body},
     *                  {@code reply_to_id} (optional, for threading)
     * @throws Exception if the SMTP session fails or the account is not found
     * @since v2026.2.1
     */
    public void sendEmail(String accountId, String owner, Map<String, Object> req) throws Exception {
        Map<String, Object> account = getAccountWithPassword(accountId, owner);
        if (account == null) throw new NoSuchElementException("Email account not found: " + accountId);

        String password = cryptoService.decryptKey((String) account.get("password_enc"));
        Properties props = buildSmtpProps(account);
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication((String) account.get("username"), password);
            }
        });

        Message message = new MimeMessage(session);
        String displayName = (String) account.get("display_name");
        String fromAddr = (String) account.get("username");
        message.setFrom(displayName != null && !displayName.isBlank()
                ? new InternetAddress(fromAddr, displayName, StandardCharsets.UTF_8.name())
                : new InternetAddress(fromAddr));

        message.setRecipients(Message.RecipientType.TO,
                InternetAddress.parse((String) req.get("to")));

        String cc = (String) req.get("cc");
        if (cc != null && !cc.isBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }

        message.setSubject((String) req.getOrDefault("subject", ""));
        message.setSentDate(new Date());

        // Reply-To threading
        String replyToId = (String) req.get("reply_to_id");
        if (replyToId != null) {
            List<Map<String, Object>> orig = db.queryForList(
                    "SELECT message_id FROM email_messages WHERE id = ?", replyToId);
            if (!orig.isEmpty()) {
                message.setHeader("In-Reply-To", (String) orig.get(0).get("message_id"));
                message.setHeader("References", (String) orig.get(0).get("message_id"));
            }
        }

        // Add signature if configured
        String body = (String) req.getOrDefault("body", "");
        String signature = (String) account.get("signature");
        if (signature != null && !signature.isBlank()) {
            body = body + "\n\n--\n" + signature;
        }

        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "utf-8");
        multipart.addBodyPart(textPart);
        message.setContent(multipart);

        Transport.send(message);
        log.info("[email] Sent email from account {} to {}", accountId, req.get("to"));
        eventBus.fire("email.sent", owner, Map.of("account_id", accountId, "to", req.get("to")));
    }

    // ── AI Triage ─────────────────────────────────────────────────────────────

    /**
     * Asynchronously triages the given message via the LLM, updating urgency score,
     * spam flag, AI summary, and tags in the database.
     *
     * @param messageId the message ID to triage
     * @param accountId the account the message belongs to
     * @param owner     the user ID (may be {@code null} for background poller runs)
     * @since v2026.2.1
     */
    public void triageMessage(String messageId, String accountId, String owner) {
        Thread.ofVirtual().name("email-triage-" + messageId).start(() -> {
            try {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT subject, from_addr, body_text FROM email_messages WHERE id = ?", messageId);
                if (rows.isEmpty()) return;

                Map<String, Object> msg = rows.get(0);
                String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
                String model = databaseService.getSetting("defaultModel", "llama3.2");

                String prompt = """
                        Analyze this email and respond with JSON:
                        {
                          "urgency": <1-5>,
                          "tags": ["work"|"personal"|"newsletter"|"spam"|"finance"|"legal"|"action_required"],
                          "summary": "<one sentence>",
                          "is_spam": <true|false>
                        }

                        From: %s
                        Subject: %s
                        Body: %s""".formatted(
                        msg.get("from_addr"),
                        msg.get("subject"),
                        truncate((String) msg.get("body_text"), 1000));

                String response = callLlm(ollamaUrl, model, prompt);
                if (response == null) return;

                // Parse JSON from response
                int start = response.indexOf('{');
                int end = response.lastIndexOf('}');
                if (start < 0 || end <= start) return;

                Map<String, Object> triage = mapper.readValue(response.substring(start, end + 1),
                        new TypeReference<>() {});

                int urgency = ((Number) triage.getOrDefault("urgency", 3)).intValue();
                boolean isSpam = Boolean.TRUE.equals(triage.get("is_spam"));
                String summary = (String) triage.getOrDefault("summary", "");
                String tagsJson = mapper.writeValueAsString(triage.getOrDefault("tags", List.of()));

                db.update("""
                        UPDATE email_messages SET urgency_score=?, is_spam=?, ai_summary=?, ai_tags_json=?
                        WHERE id=?""",
                        urgency, isSpam ? 1 : 0, summary, tagsJson, messageId);

            } catch (Exception e) {
                log.debug("[email-triage] Failed for {}: {}", messageId, e.getMessage());
            }
        });
    }

    // ── AI Reply Draft ────────────────────────────────────────────────────────

    /**
     * Generates an AI reply draft for the given message using the configured LLM.
     *
     * @param messageId the source message ID to reply to
     * @param accountId the account the message belongs to
     * @param owner     the requesting user ID
     * @return the generated reply body text, or {@code ""} on failure
     * @since v2026.2.1
     */
    public String generateReplyDraft(String messageId, String accountId, String owner) {
        try {
            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT subject, from_addr, body_text FROM email_messages WHERE id = ? AND account_id = ?",
                    messageId, accountId);
            if (rows.isEmpty()) return "";

            Map<String, Object> msg = rows.get(0);
            String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
            String model = databaseService.getSetting("defaultModel", "llama3.2");

            String prompt = """
                    Write a professional, helpful reply to this email. Keep it concise. \
                    Only write the reply body — no subject, no greeting like "Dear", just the natural reply content.

                    From: %s
                    Subject: %s
                    Email: %s""".formatted(msg.get("from_addr"), msg.get("subject"),
                    truncate((String) msg.get("body_text"), 1500));

            String draft = callLlm(ollamaUrl, model, prompt);
            return draft != null ? draft.trim() : "";
        } catch (Exception e) {
            log.warn("[email] Reply draft generation failed: {}", e.getMessage());
            return "";
        }
    }

    // ── Background IMAP Polling ────────────────────────────────────────────────

    /**
     * Scheduled IMAP polling dispatcher — checks every enabled account once per minute
     * and triggers a per-account poll on a virtual thread when its poll interval has elapsed.
     *
     * @since v2026.2.1
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void pollAllAccounts() {
        try {
            List<Map<String, Object>> accounts = db.queryForList(
                    "SELECT id, owner FROM email_accounts WHERE enabled = 1");
            for (Map<String, Object> account : accounts) {
                String accountId = (String) account.get("id");
                String owner = (String) account.get("owner");
                long pollInterval = getPollInterval(accountId);
                long now = System.currentTimeMillis() / 1000;
                long last = lastPoll.getOrDefault(accountId, 0L);

                if (now - last >= pollInterval) {
                    lastPoll.put(accountId, now);
                    Thread.ofVirtual().name("email-poll-" + accountId).start(() -> {
                        try {
                            pollAccount(accountId, owner);
                        } catch (Exception e) {
                            log.debug("[email-poll] Error polling {}: {}", accountId, e.getMessage());
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("[email-poll] Scheduler error: {}", e.getMessage());
        }
    }

    private void pollAccount(String accountId, String owner) throws Exception {
        Map<String, Object> account = getAccountWithPassword(accountId, owner);
        if (account == null) return;

        String password = cryptoService.decryptKey((String) account.get("password_enc"));
        Properties props = buildImapProps(account);
        Session session = Session.getInstance(props);

        try (Store store = session.getStore("imap")) {
            store.connect((String) account.get("imap_host"),
                    ((Number) account.get("imap_port")).intValue(),
                    (String) account.get("username"), password);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int total = inbox.getMessageCount();
            int start = Math.max(1, total - FETCH_BATCH + 1);
            Message[] messages = inbox.getMessages(start, total);

            int newCount = 0;
            for (Message msg : messages) {
                try {
                    newCount += importMessage(accountId, msg);
                } catch (Exception e) {
                    log.debug("[email-poll] Failed to import message: {}", e.getMessage());
                }
            }

            if (newCount > 0) {
                log.info("[email-poll] Fetched {} new messages for account {}", newCount, accountId);
                eventBus.fire("email.received", owner, Map.of("account_id", accountId, "count", newCount));
            }

            db.update("UPDATE email_accounts SET last_polled_at=? WHERE id=?",
                    Instant.now().toString(), accountId);
        }
    }

    private int importMessage(String accountId, Message msg) throws Exception {
        String messageId = getHeader(msg, "Message-ID");
        if (messageId == null) messageId = UUID.randomUUID().toString();

        // Check if already imported
        int exists = db.queryForObject(
                "SELECT COUNT(*) FROM email_messages WHERE account_id=? AND message_id=?",
                Integer.class, accountId, messageId);
        if (exists > 0) return 0;

        String subject = msg.getSubject() != null
                ? MimeUtility.decodeText(msg.getSubject()) : "";
        String fromAddr = msg.getFrom() != null ? msg.getFrom()[0].toString() : "";
        String toAddr = Arrays.stream(msg.getRecipients(Message.RecipientType.TO) != null
                ? msg.getRecipients(Message.RecipientType.TO) : new Address[0])
                .map(Address::toString).reduce("", (a, b) -> a.isBlank() ? b : a + ", " + b);

        String inReplyTo = getHeader(msg, "In-Reply-To");
        String threadId = inReplyTo != null ? resolveThreadId(accountId, inReplyTo) : messageId;

        String bodyText = extractText(msg);
        Date sentDate = msg.getSentDate();
        String sentAt = sentDate != null ? sentDate.toInstant().toString() : Instant.now().toString();

        String id = "eml-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);

        db.update("""
                INSERT INTO email_messages (id, account_id, message_id, in_reply_to, thread_id,
                  folder, subject, from_addr, to_addr, date_sent, body_text, is_read, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, accountId, messageId, inReplyTo, threadId,
                "INBOX", subject, fromAddr, toAddr, sentAt, bodyText, 0, Instant.now().toString());

        // Async AI triage
        triageMessage(id, accountId, null);
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Properties buildImapProps(Map<String, Object> account) {
        Properties props = new Properties();
        String security = (String) account.getOrDefault("imap_security", "SSL");
        if ("SSL".equalsIgnoreCase(security)) {
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.ssl.enable", "true");
        } else {
            props.put("mail.store.protocol", "imap");
        }
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        return props;
    }

    private Properties buildSmtpProps(Map<String, Object> account) {
        Properties props = new Properties();
        String security = (String) account.getOrDefault("smtp_security", "STARTTLS");
        props.put("mail.smtp.host", account.get("smtp_host"));
        props.put("mail.smtp.port", account.get("smtp_port").toString());
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        if ("STARTTLS".equalsIgnoreCase(security)) {
            props.put("mail.smtp.starttls.enable", "true");
        } else if ("SSL".equalsIgnoreCase(security)) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        return props;
    }

    private Map<String, Object> getAccountWithPassword(String accountId, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM email_accounts WHERE id = ? AND (owner = ? OR team_id IS NOT NULL)",
                accountId, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long getPollInterval(String accountId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT poll_interval_s FROM email_accounts WHERE id=?", accountId);
        if (rows.isEmpty()) return DEFAULT_POLL_INTERVAL_S;
        Object val = rows.get(0).get("poll_interval_s");
        return val != null ? ((Number) val).longValue() : DEFAULT_POLL_INTERVAL_S;
    }

    private String resolveThreadId(String accountId, String inReplyTo) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT thread_id FROM email_messages WHERE account_id=? AND message_id=?",
                accountId, inReplyTo);
        if (!rows.isEmpty() && rows.get(0).get("thread_id") != null) {
            return (String) rows.get(0).get("thread_id");
        }
        return inReplyTo; // Use the referenced message ID as thread root
    }

    private String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return truncate(part.getContent().toString(), 10000);
        } else if (part.isMimeType("text/html")) {
            String html = part.getContent().toString();
            // Strip HTML tags
            return truncate(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(), 10000);
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String text = extractText(mp.getBodyPart(i));
                if (text != null) return text;
            }
        }
        return null;
    }

    private String getHeader(Message msg, String name) {
        try {
            String[] values = msg.getHeader(name);
            return values != null && values.length > 0 ? values[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapMessageRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        try {
            String tagsJson = (String) row.get("ai_tags_json");
            r.put("ai_tags", tagsJson != null ? mapper.readValue(tagsJson, List.class) : List.of());
            r.remove("ai_tags_json");
        } catch (Exception e) {
            r.put("ai_tags", List.of());
        }
        return r;
    }

    private String callLlm(String ollamaUrl, String model, String prompt) {
        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false,
                    "options", Map.of("num_predict", 512, "temperature", 0.1)
            );
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl.replaceAll("/+$", "") + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body()).path("message").path("content").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
