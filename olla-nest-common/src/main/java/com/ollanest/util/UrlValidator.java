package com.ollanest.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;

/**
 * SSRF protection utility that validates provider base URLs before they are
 * persisted or used for outbound HTTP calls.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Created as part of the HIGH-3 security fix to prevent Server-Side Request
 * Forgery attacks via the admin provider management API. Any URL submitted as a
 * provider base URL is passed through {@link #isSafeUrl(String)} before being
 * stored or used for API calls, ensuring the application cannot be turned into
 * a proxy to internal network services.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>DNS resolution is performed at validation time so that DNS-rebinding
 * attacks (where a benign hostname later resolves to a private IP) are
 * mitigated at the point of configuration, not at call time.</li>
 * <li>Both JDK address classification methods and explicit byte-level RFC-1918
 * checks are applied as defence-in-depth against JDK version differences.</li>
 * <li>This class is a non-instantiable static utility; the constructor is
 * private.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — created during security hardening (HIGH-3 SSRF
 * protection)</li>
 * <li>v2026.1.4 — no functional changes; retained as part of audit pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
public class UrlValidator {

	/**
	 * Private constructor — this is a non-instantiable static utility class.
	 *
	 * @since v2026.1.0
	 */
	private UrlValidator() {
	}

	/**
	 * Returns {@code true} if the URL is safe to use as a provider base URL.
	 *
	 * <p>
	 * Performs the following checks in order:
	 * <ol>
	 * <li>Rejects blank or {@code null} input.</li>
	 * <li>Parses the URL; rejects if malformed.</li>
	 * <li>Rejects schemes other than {@code http} and {@code https}.</li>
	 * <li>Resolves all DNS addresses for the host and rejects if any resolve to a
	 * private, loopback, or link-local range (see
	 * {@link #isPrivateOrLoopback}).</li>
	 * </ol>
	 *
	 * @param urlStr the URL string to validate; may be {@code null}
	 * @return {@code true} if the URL passes all safety checks, {@code false} for
	 *         any invalid or unsafe input
	 * @since v2026.1.0
	 */
	public static boolean isSafeUrl(String urlStr) {
		if (urlStr == null || urlStr.isBlank())
			return false;
		try {
			URL url = URI.create(urlStr).toURL();
			String scheme = url.getProtocol();
			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
				return false;

			String host = url.getHost();
			if (host == null || host.isBlank())
				return false;

			InetAddress[] addresses = InetAddress.getAllByName(host);
			for (InetAddress addr : addresses) {
				if (isPrivateOrLoopback(addr))
					return false;
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Returns {@code true} if the given address falls within a private, loopback,
	 * or link-local range that must not be reachable via user-supplied provider
	 * URLs.
	 *
	 * <p>
	 * Checks applied (in order):
	 * <ul>
	 * <li>JDK loopback detection (covers {@code 127.x.x.x} and {@code ::1})</li>
	 * <li>JDK link-local detection (covers {@code 169.254.x.x} and
	 * {@code fe80::/10})</li>
	 * <li>JDK site-local detection (covers {@code 10.x}, {@code 172.16–31.x},
	 * {@code 192.168.x})</li>
	 * <li>Explicit byte-level RFC-1918 checks as defence-in-depth against JDK
	 * version differences</li>
	 * </ul>
	 *
	 * @param addr the resolved {@link InetAddress} to evaluate
	 * @return {@code true} if the address is private, loopback, or link-local
	 * @since v2026.1.0
	 */
	private static boolean isPrivateOrLoopback(InetAddress addr) {
		if (addr.isLoopbackAddress())
			return true;
		if (addr.isLinkLocalAddress())
			return true;
		if (addr.isSiteLocalAddress())
			return true;
		// 0.0.0.0 / :: — wildcard addresses route to localhost on many stacks (SSRF).
		if (addr.isAnyLocalAddress())
			return true;
		byte[] b = addr.getAddress();
		// IPv6 unique-local address (ULA) fc00::/7 — private, not covered by the JDK
		// site-local check; must be blocked (e.g. http://[fc00::1]/).
		if (b.length == 16 && (b[0] & 0xFE) == 0xFC)
			return true;
		if (b.length == 4) {
			int b0 = b[0] & 0xFF;
			int b1 = b[1] & 0xFF;
			// 10.0.0.0/8
			if (b0 == 10)
				return true;
			// 172.16.0.0/12
			if (b0 == 172 && b1 >= 16 && b1 <= 31)
				return true;
			// 192.168.0.0/16
			if (b0 == 192 && b1 == 168)
				return true;
			// 127.0.0.0/8
			if (b0 == 127)
				return true;
			// 169.254.0.0/16
			if (b0 == 169 && b1 == 254)
				return true;
		}
		// IPv6 ::1 is caught by isLoopbackAddress()
		return false;
	}
}
