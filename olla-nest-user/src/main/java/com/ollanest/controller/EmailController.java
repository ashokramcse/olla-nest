package com.ollanest.controller;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.EmailService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for the integrated email client: account management, inbox
 * browsing, message actions, sending, and AI-assisted reply drafting.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Brings IMAP/SMTP email accounts into the assistant so users can read, triage,
 * and reply to mail in one place — and have the model draft replies. This
 * controller is the HTTP surface over those operations; all protocol handling,
 * persistence, and AI generation live in {@link EmailService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Endpoints are organised into account, message, send, and AI groups
 * (marked by the section comments below).</li>
 * <li>Account-level operations scope to {@code user.id}; message-level
 * operations are scoped by {@code accountId} (which itself belongs to the
 * authenticated user) and only require authentication.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/email")
public class EmailController extends BaseController {

	/** Service backing IMAP/SMTP access, persistence, and AI reply drafting. */
	private final EmailService emailService;

	/**
	 * Constructor-injects the email service.
	 *
	 * @param emailService the service backing all email operations
	 * @since v2026.2.1
	 */
	public EmailController(EmailService emailService) {
		this.emailService = emailService;
	}

	/**
	 * Ownership guard for every account-scoped endpoint (BUG-044). The message
	 * read/list/star/delete/reply-draft service queries scope only by
	 * {@code account_id}, so without this check any authenticated user could
	 * access another user's private email by putting the victim's {@code accountId}
	 * in the URL. Returns a 404 response if the account is not visible to the user
	 * (owner or shared team), or {@code null} if access is allowed.
	 *
	 * @param user      the authenticated user
	 * @param accountId the account id from the path
	 * @return a 404 {@link ResponseEntity} to return, or {@code null} if allowed
	 */
	private ResponseEntity<?> denyIfNotOwned(User user, String accountId) {
		return emailService.getAccount(accountId, user.id) == null ? notFound("Account not found") : null;
	}

	// ── Accounts ──────────────────────────────────────────────────────────────

	/**
	 * Lists the calling user's configured email accounts.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return an OK response with the user's accounts
	 * @since v2026.2.1
	 */
	@GetMapping("/accounts")
	public ResponseEntity<?> listAccounts(HttpServletRequest req) {
		User user = requireAuth(req);
		return ok(emailService.listAccounts(user.id));
	}

	/**
	 * Adds a new email account for the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the account configuration (server, credentials, etc.)
	 * @return a CREATED response with the persisted account
	 * @since v2026.2.1
	 */
	@PostMapping("/accounts")
	public ResponseEntity<?> createAccount(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return created(emailService.createAccount(user.id, body));
	}

	/**
	 * Fetches a single email account owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the account to fetch
	 * @return an OK response with the account, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/accounts/{id}")
	public ResponseEntity<?> getAccount(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		var account = emailService.getAccount(id, user.id);
		if (account == null)
			return notFound("Account not found");
		return ok(account);
	}

	/**
	 * Deletes an email account owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the account to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/accounts/{id}")
	public ResponseEntity<?> deleteAccount(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		emailService.deleteAccount(id, user.id);
		return ok(Map.of("ok", true));
	}

	// ── Messages ──────────────────────────────────────────────────────────────

	/**
	 * Lists messages in a folder of one of the calling user's accounts, paged.
	 *
	 * @param req       the HTTP request, used to resolve the authenticated user
	 * @param accountId the account whose mailbox is browsed
	 * @param folder    the mailbox folder (default {@code "INBOX"})
	 * @param page      1-based page number (default 1)
	 * @param pageSize  messages per page (default 30)
	 * @return an OK response with the page of messages
	 * @since v2026.2.1
	 */
	@GetMapping("/accounts/{accountId}/messages")
	public ResponseEntity<?> listMessages(HttpServletRequest req, @PathVariable String accountId,
			@RequestParam(defaultValue = "INBOX") String folder, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "30") int pageSize) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		return ok(emailService.listMessages(accountId, user.id, folder, page, pageSize));
	}

	/**
	 * Fetches a single message.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param accountId the account the message belongs to
	 * @param messageId the id of the message to fetch
	 * @return an OK response with the message, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/accounts/{accountId}/messages/{messageId}")
	public ResponseEntity<?> getMessage(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String messageId) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		var msg = emailService.getMessage(messageId, accountId);
		if (msg == null)
			return notFound("Message not found");
		return ok(msg);
	}

	/**
	 * Fetches a conversation thread.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param accountId the account the thread belongs to
	 * @param threadId  the id of the thread to fetch
	 * @return an OK response with the thread's messages
	 * @since v2026.2.1
	 */
	@GetMapping("/accounts/{accountId}/threads/{threadId}")
	public ResponseEntity<?> getThread(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String threadId) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		return ok(emailService.getThread(threadId, accountId));
	}

	/**
	 * Marks a message as read.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param accountId the account the message belongs to
	 * @param messageId the id of the message to mark read
	 * @return an OK response acknowledging the change
	 * @since v2026.2.1
	 */
	@PostMapping("/accounts/{accountId}/messages/{messageId}/read")
	public ResponseEntity<?> markRead(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String messageId) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		emailService.markRead(messageId, accountId);
		return ok(Map.of("ok", true));
	}

	/**
	 * Sets or clears the starred flag on a message.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param accountId the account the message belongs to
	 * @param messageId the id of the message to star or unstar
	 * @param body      request payload; {@code starred} carries the desired state
	 * @return an OK response acknowledging the change
	 * @since v2026.2.1
	 */
	@PostMapping("/accounts/{accountId}/messages/{messageId}/star")
	public ResponseEntity<?> markStarred(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String messageId, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		boolean starred = Boolean.TRUE.equals(body.get("starred"));
		emailService.markStarred(messageId, accountId, starred);
		return ok(Map.of("ok", true));
	}

	/**
	 * Deletes a message.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param accountId the account the message belongs to
	 * @param messageId the id of the message to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/accounts/{accountId}/messages/{messageId}")
	public ResponseEntity<?> deleteMessage(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String messageId) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		emailService.deleteMessage(messageId, accountId);
		return ok(Map.of("ok", true));
	}

	// ── Send ──────────────────────────────────────────────────────────────────

	/**
	 * Sends an email from one of the calling user's accounts.
	 *
	 * @param req       the HTTP request, used to resolve the authenticated user
	 * @param accountId the account to send from
	 * @param body      the message to send (recipients, subject, body, etc.)
	 * @return an OK response on success, or a 500 if sending fails
	 * @since v2026.2.1
	 */
	@PostMapping("/accounts/{accountId}/send")
	public ResponseEntity<?> send(HttpServletRequest req, @PathVariable String accountId,
			@RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		try {
			emailService.sendEmail(accountId, user.id, body);
			return ok(Map.of("ok", true));
		} catch (NoSuchElementException e) {
			// Unknown account is a 404, not a server fault (BUG-039).
			return notFound(e.getMessage());
		} catch (IllegalArgumentException e) {
			// Invalid/missing send fields are a 400, not a 500.
			return badRequest(e.getMessage());
		} catch (Exception e) {
			return serverError("Failed to send email: " + e.getMessage());
		}
	}

	// ── AI ────────────────────────────────────────────────────────────────────

	/**
	 * Generates an AI-drafted reply to a message.
	 *
	 * @param req       the HTTP request, used to resolve the authenticated user
	 * @param accountId the account the message belongs to
	 * @param messageId the id of the message being replied to
	 * @return an OK response whose {@code draft} entry holds the generated reply
	 * @since v2026.2.1
	 */
	@PostMapping("/accounts/{accountId}/messages/{messageId}/reply-draft")
	public ResponseEntity<?> replyDraft(HttpServletRequest req, @PathVariable String accountId,
			@PathVariable String messageId) {
		User user = requireAuth(req);
		ResponseEntity<?> deny = denyIfNotOwned(user, accountId);
		if (deny != null) return deny;
		String draft = emailService.generateReplyDraft(messageId, accountId, user.id);
		return ok(Map.of("draft", draft));
	}
}
