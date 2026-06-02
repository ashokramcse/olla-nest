package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.*;
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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Email system: IMAP inbox management and SMTP sending with AI triage.
 *
 * Supports multiple accounts per user (personal + team shared inboxes).
 * AI triage runs urgency scoring (1-5), auto-tagging, summarization,
 * and spam detection via the configured LLM.
 *
 * Background polling runs every {@value #DEFAULT_POLL_INTERVAL_S} seconds
 * per account. Each account's poller runs on a virtual thread.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final int DEFAULT_POLL_INTERVAL_S = 300;
    private static final int FETCH_BATCH = 50;

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final CryptoService cryptoService;
    private final DatabaseService databaseService;
    private final EventBusService eventBus;

    // accountId -> last poll timestamp
    private final Map<String, Long> lastPoll = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public EmailService(JdbcTemplate db, ObjectMapper mapper, CryptoService cryptoService,
            DatabaseService databaseService, EventBusService eventBus) {
        this.db = db;
        this.mapper = mapper;
        this.cryptoService = cryptoService;
        this.databaseService = databaseService;
        this.eventBus = eventBus;
    }

    // ── Account Management ────────────────────────────────────────────────────

    public Map<String, Object> createAccount(String owner, Map<String, Object> req) {
        String id = "email-" + Long.toString(System.currentTimeMillis(), 36);
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
                req.get("imap_host"),
                req.getOrDefault("imap_port", 993),
                req.getOrDefault("imap_security", "SSL"),
                req.get("smtp_host"),
                req.getOrDefault("smtp_port", 587),
                req.getOrDefault("smtp_security", "STARTTLS"),
                req.get("username"),
                encPassword,
                req.getOrDefault("display_name", ""),
                req.getOrDefault("signature", ""),
                req.getOrDefault("poll_interval_s", DEFAULT_POLL_INTERVAL_S),
                1,
                req.get("team_id"),
                now, now);

        return getAccount(id, owner);
    }

    public Map<String, Object> getAccount(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM email_accounts WHERE id = ? AND (owner = ? OR team_id IS NOT NULL)", id, owner);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.remove("password_enc"); // never expose password
        return row;
    }

    public List<Map<String, Object>> listAccounts(String owner) {
        return db.queryForList(
                "SELECT id, owner, name, imap_host, smtp_host, username, display_name, enabled, last_polled_at, team_id FROM email_accounts WHERE owner = ? OR team_id IN (SELECT team FROM users WHERE id = ?)",
                owner, owner)
                .stream().map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)).toList();
    }

    public void deleteAccount(String id, String owner) {
        db.update("DELETE FROM email_accounts WHERE id = ? AND owner = ?", id, owner);
    }

    // ── IMAP: List Messages ────────────────────────────────────────────────────

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

    public Map<String, Object> getMessage(String id, String accountId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM email_messages WHERE id = ? AND account_id = ?", id, accountId);
        if (rows.isEmpty()) return null;
        markRead(id, accountId);
        return mapMessageRow(rows.get(0));
    }

    public List<Map<String, Object>> getThread(String threadId, String accountId) {
        return db.queryForList(
                "SELECT * FROM email_messages WHERE thread_id = ? AND account_id = ? ORDER BY date_sent ASC",
                threadId, accountId)
                .stream().map(this::mapMessageRow).toList();
    }

    public void markRead(String id, String accountId) {
        db.update("UPDATE email_messages SET is_read=1 WHERE id=? AND account_id=?", id, accountId);
    }

    public void markStarred(String id, String accountId, boolean starred) {
        db.update("UPDATE email_messages SET is_starred=? WHERE id=? AND account_id=?",
                starred ? 1 : 0, id, accountId);
    }

    public void deleteMessage(String id, String accountId) {
        db.update("DELETE FROM email_messages WHERE id=? AND account_id=?", id, accountId);
    }

    // ── SMTP: Send ────────────────────────────────────────────────────────────

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
            jakarta.mail.Message[] messages = inbox.getMessages(start, total);

            int newCount = 0;
            for (jakarta.mail.Message msg : messages) {
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

    private int importMessage(String accountId, jakarta.mail.Message msg) throws Exception {
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

    private String getHeader(jakarta.mail.Message msg, String name) {
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
