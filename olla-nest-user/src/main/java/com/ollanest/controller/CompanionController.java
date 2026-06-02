package com.ollanest.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ollanest.model.User;
import com.ollanest.service.ApiTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.net.NetworkInterface;
import java.util.*;

/**
 * Companion bridge — LAN discovery and QR-code pairing for mobile clients.
 * Generates oly_ bearer tokens for paired devices.
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController extends BaseController {

    private final ApiTokenService tokenService;

    public CompanionController(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** Return server discovery info (LAN IP, port, version). */
    @GetMapping("/info")
    public ResponseEntity<?> info(HttpServletRequest req) {
        requireAuth(req);
        return ok(Map.of(
                "host", getLanIp(),
                "port", req.getServerPort(),
                "version", "2026.2.0",
                "name", "Olla Nest"
        ));
    }

    /** Mint a companion bearer token and return a QR code for pairing. */
    @PostMapping("/pair")
    public ResponseEntity<?> pair(HttpServletRequest req,
            @RequestBody(required = false) Map<String, Object> body) {
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

        return created(Map.of(
                "token_prefix", token.get("token_prefix"),
                "pair_url", pairUrl,
                "qr_base64", qrBase64,
                "device_name", deviceName
        ));
    }

    /** Ping endpoint for paired devices to verify connectivity. */
    @GetMapping("/ping")
    public ResponseEntity<?> ping(HttpServletRequest req) {
        requireAuth(req);
        return ok(Map.of("ok", true, "ts", System.currentTimeMillis()));
    }

    private String getLanIp() {
        try {
            // UDP connect trick — reveals egress interface IP
            java.net.DatagramSocket s = new java.net.DatagramSocket();
            s.connect(java.net.InetAddress.getByName("8.8.8.8"), 80);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            if (ip != null && !ip.startsWith("127.")) return ip;
        } catch (Exception ignore) {}
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {}
        return "localhost";
    }

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
