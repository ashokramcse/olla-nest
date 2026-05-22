package com.ollanest.util;

import java.net.InetAddress;
import java.net.URL;

/**
 * Validates URLs for SSRF protection.
 * Rejects non-http/https schemes and private/loopback IP ranges.
 */
public class UrlValidator {

    private UrlValidator() {}

    /**
     * Returns true if the URL is safe to use as a provider base URL.
     * Rejects: non-http/https schemes, private IPs, loopback addresses.
     */
    public static boolean isSafeUrl(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return false;
        try {
            URL url = new URL(urlStr);
            String scheme = url.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;

            String host = url.getHost();
            if (host == null || host.isBlank()) return false;

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isPrivateOrLoopback(addr)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPrivateOrLoopback(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;
        if (addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            // 10.0.0.0/8
            if (b0 == 10) return true;
            // 172.16.0.0/12
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true;
            // 127.0.0.0/8
            if (b0 == 127) return true;
            // 169.254.0.0/16
            if (b0 == 169 && b1 == 254) return true;
        }
        // IPv6 ::1 is caught by isLoopbackAddress()
        return false;
    }
}
