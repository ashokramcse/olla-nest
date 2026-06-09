package com.ollanest.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.ContactsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for the contacts address book: CRUD, search, and vCard
 * export.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Provides the user-facing surface over a personal address book, including
 * substring search and standards-compliant vCard ({@code .vcf}) export for
 * interoperability with other contact managers. Persistence and ownership
 * enforcement are delegated to {@link ContactsService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>{@link #exportVCard} returns a downloadable {@code text/vcard} attachment
 * rather than a JSON envelope.</li>
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
@RequestMapping("/api/contacts")
public class ContactsController extends BaseController {

	/** Service backing contact persistence, search, and vCard export. */
	private final ContactsService contactsService;

	/**
	 * Constructor-injects the contacts service.
	 *
	 * @param contactsService the service backing all contact operations
	 * @since v2026.2.1
	 */
	public ContactsController(ContactsService contactsService) {
		this.contactsService = contactsService;
	}

	/**
	 * Lists the calling user's contacts.
	 *
	 * @param req   the HTTP request, used to resolve the authenticated user
	 * @param limit maximum number of contacts to return (default 100)
	 * @return an OK response with the user's contacts
	 * @since v2026.2.1
	 */
	@GetMapping
	public ResponseEntity<?> list(HttpServletRequest req, @RequestParam(defaultValue = "100") int limit) {
		User user = requireAuth(req);
		return ok(contactsService.list(user.id, limit));
	}

	/**
	 * Searches the calling user's contacts.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param q   the search query
	 * @return an OK response with the matching contacts
	 * @since v2026.2.1
	 */
	@GetMapping("/search")
	public ResponseEntity<?> search(HttpServletRequest req, @RequestParam String q) {
		User user = requireAuth(req);
		return ok(contactsService.search(user.id, q));
	}

	/**
	 * Fetches a single contact owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the contact to fetch
	 * @return an OK response with the contact, or a 404 if it does not exist
	 * @since v2026.2.1
	 */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		var contact = contactsService.getById(id, user.id);
		if (contact == null)
			return notFound("Contact not found");
		return ok(contact);
	}

	/**
	 * Creates a new contact for the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param body the contact definition
	 * @return a CREATED response with the persisted contact
	 * @since v2026.2.1
	 */
	@PostMapping
	public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return created(contactsService.create(user.id, body));
	}

	/**
	 * Updates an existing contact owned by the calling user.
	 *
	 * @param req  the HTTP request, used to resolve the authenticated user
	 * @param id   the id of the contact to update
	 * @param body the updated contact fields
	 * @return an OK response with the updated contact
	 * @since v2026.2.1
	 */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
			@RequestBody Map<String, Object> body) {
		User user = requireAuth(req);
		return ok(contactsService.update(id, user.id, body));
	}

	/**
	 * Deletes a contact owned by the calling user.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @param id  the id of the contact to delete
	 * @return an OK response acknowledging the deletion
	 * @since v2026.2.1
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
		User user = requireAuth(req);
		contactsService.delete(id, user.id);
		return ok(Map.of("ok", true));
	}

	/**
	 * Exports all of the calling user's contacts as a downloadable vCard file.
	 *
	 * @param req the HTTP request, used to resolve the authenticated user
	 * @return a {@code text/vcard} attachment containing the exported contacts
	 * @since v2026.2.1
	 */
	@GetMapping("/export.vcf")
	public ResponseEntity<String> exportVCard(HttpServletRequest req) {
		User user = requireAuth(req);
		String vcf = contactsService.exportVCard(user.id);
		return ResponseEntity.ok().header("Content-Type", "text/vcard; charset=utf-8")
				.header("Content-Disposition", "attachment; filename=\"contacts.vcf\"").body(vcf);
	}
}
