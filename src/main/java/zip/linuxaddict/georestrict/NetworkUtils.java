/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Utility class for private/reserved IP detection.
 */
public class NetworkUtils {

    private NetworkUtils() {
        // Utility class â€” no instantiation
    }

    /**
     * Checks whether the given IP address is a private, loopback, or link-local address.
     *
     * Covers:
     *   IPv4: 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 0.0.0.0
     *   IPv6: ::1, fe80::/10, fc00::/7, 0:0:0:0:0:0:0:1
     *
     * @param ip the IP address string to check
     * @return true if the IP is private/reserved, false otherwise
     */
    public static boolean isPrivateIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Quick literal checks before parsing
        if (ip.equals("0.0.0.0")) {
            return true;
        }

        try {
            InetAddress addr = InetAddress.getByName(ip);
            byte[] bytes = addr.getAddress();

            if (bytes.length == 4) {
                // IPv4
                return isPrivateIpv4(bytes);
            } else if (bytes.length == 16) {
                // IPv6
                return isPrivateIpv6(bytes, addr);
            }
        } catch (UnknownHostException e) {
            // If we can't parse it, treat it as non-private
            return false;
        }

        return false;
    }

    private static boolean isPrivateIpv4(byte[] bytes) {
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;

        // 127.0.0.0/8 â€” Loopback
        if (b0 == 127) {
            return true;
        }

        // 10.0.0.0/8 â€” Private
        if (b0 == 10) {
            return true;
        }

        // 172.16.0.0/12 â€” Private (172.16.x.x â€“ 172.31.x.x)
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }

        // 192.168.0.0/16 â€” Private
        if (b0 == 192 && b1 == 168) {
            return true;
        }

        // 169.254.0.0/16 â€” Link-local
        if (b0 == 169 && b1 == 254) {
            return true;
        }

        // 0.0.0.0
        if (b0 == 0 && b1 == 0 && (bytes[2] & 0xFF) == 0 && (bytes[3] & 0xFF) == 0) {
            return true;
        }

        return false;
    }

    private static boolean isPrivateIpv6(byte[] bytes, InetAddress addr) {
        // ::1 (loopback)
        if (addr.isLoopbackAddress()) {
            return true;
        }

        // fe80::/10 â€” Link-local
        if (addr.isLinkLocalAddress()) {
            return true;
        }

        // fc00::/7 â€” Unique local address (ULA)
        // First byte: fc (11111100) or fd (11111101), i.e. top 7 bits = 1111110
        int firstByte = bytes[0] & 0xFF;
        if ((firstByte & 0xFE) == 0xFC) {
            return true;
        }

        return false;
    }
}

