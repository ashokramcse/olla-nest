package com.ollanest.controller;

import java.io.ByteArrayOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ollanest.model.User;
import com.ollanest.service.ApiTokenService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller acting as the companion bridge: LAN discovery and QR-code
 * pairing for mobile clients.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Mobile companion apps need a frictionless way to discover and authenticate
 * against a self-hosted server on the local network. This controller advertises
 * the server's LAN address, mints a scoped bearer token for a new device, and
 * encodes the pairing handoff as a scannable QR code so the user does not have
 * to type credentials.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Pairing mints a real API token and is therefore restricted to admins via
 * {@link BaseController#requireAdminUser}; discovery and ping only require
 * authentication.</li>
 * <li>The LAN IP is resolved with a UDP "connect" trick that reveals the egress
 * interface, falling back to interface enumeration and finally
 * {@code localhost}.</li>
 * <li>The QR code encodes an {@code ollanest://pair} deep link carrying host,
 * port, and the freshly minted token.</li>
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
@RequestMapping("/api/companion")
public class CompanionController extends BaseController {

	/** Service used to mint scoped bearer tokens for paired devices. */
	private final ApiTokenService tokenService;

	/**
	 * Constructor-injects the API token service.
	 *
	 * @param tokenService the service used to mint device tokens
	 * @since v2026.2.1
	 */
	public CompanionController(ApiTokenService tokenService) {
		this.tokenService = tokenService;
	}

	/**
	 * Returns server discovery info (LAN IP, port, version, name).
	 *
	 * @param req the HTTP request; authentication is required
	 * @return an OK response with the discovery payload
	 * @since v2026.2.1
	 */
	@GetMapping("/info")
	public ResponseEntity<?> info(HttpServletRequest req) {
		requireAuth(req);
		return ok(Map.of("host", getLanIp(), "port", req.getServerPort(), "version", "2026.2.0", "name", "Olla Nest"));
	}

	/**
	 * Mints a companion bearer token and returns a QR code for pairing.
	 *
	 * <p>
	 * Generates a {@code chat}-scoped token, builds an {@code ollanest://pair} deep
	 * link embedding the LAN host, port, and token, then renders that link as a
	 * base64 PNG QR code for the mobile client to scan.
	 *
	 * @param req  the HTTP request; must resolve to an admin user
	 * @param body optional request payload; {@code name} labels the device
	 *             (defaults to {@code "Mobile Device"})
	 * @return a CREATED response with the token prefix, pairing URL, QR image, and
	 *         device name
	 * @since v2026.2.1
	 */
	@PostMapping("/pair")
	public ResponseEntity<?> pair(HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
		User user = requireAdminUser(req);
		String deviceName = body != null ? (String) body.getOrDefault("name", "Mobile Device") : "Mobile Device";

		var token = tokenService.mint(user.id, deviceName, List.of("chat"));
		String rawToken = (String) token.get("token");

		// Build pairing URL
		String host = getLanIp();
		int port = req.getServerPort();
		String pairUrl = String.format("ollanest://pair?host=%s&port=%d&token=%s", host, port, rawToken);

		// Generate QR code
		String qrBase64 = generateQrBase64(pairUrl);

		return created(Map.of("token_prefix", token.get("token_prefix"), "pair_url", pairUrl, "qr_base64", qrBase64,
				"device_name", deviceName));
	}

	/**
	 * Lightweight connectivity check for paired devices.
	 *
	 * @param req the HTTP request; authentication is required
	 * @return an OK response with a liveness flag and server timestamp
	 * @since v2026.2.1
	 */
	@GetMapping("/ping")
	public ResponseEntity<?> ping(HttpServletRequest req) {
		requireAuth(req);
		return ok(Map.of("ok", true, "ts", System.currentTimeMillis()));
	}

	/**
	 * Resolves the server's LAN-facing IPv4 address.
	 *
	 * <p>
	 * First attempts a UDP "connect" to a public address to discover the egress
	 * interface; if that yields only a loopback address, falls back to scanning the
	 * non-loopback network interfaces, and finally returns {@code "localhost"}.
	 *
	 * @return the best-guess LAN IP, or {@code "localhost"} if none can be found
	 * @since v2026.2.1
	 */
	private String getLanIp() {
		try {
			// UDP connect trick — reveals egress interface IP
			DatagramSocket s = new DatagramSocket();
			s.connect(InetAddress.getByName("8.8.8.8"), 80);
			String ip = s.getLocalAddress().getHostAddress();
			s.close();
			if (ip != null && !ip.startsWith("127."))
				return ip;
		} catch (Exception ignore) {
		}
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface ni = interfaces.nextElement();
				if (ni.isLoopback() || !ni.isUp())
					continue;
				Enumeration<InetAddress> addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress addr = addrs.nextElement();
					if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
						return addr.getHostAddress();
					}
				}
			}
		} catch (Exception ignore) {
		}
		return "localhost";
	}

	/**
	 * Encodes the given text as a base64 PNG QR-code data URI.
	 *
	 * @param content the text to encode (the pairing deep link)
	 * @return a {@code data:image/png;base64,...} URI, or an empty string on error
	 * @since v2026.2.1
	 */
	private String generateQrBase64(String content) {
		try {
			QRCodeWriter writer = new QRCodeWriter();
			BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 300, 300);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
			return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
		} catch (Exception e) {
			return "";
		}
	}
}
