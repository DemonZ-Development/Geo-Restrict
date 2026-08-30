/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class NetworkUtils {

    private NetworkUtils() {}

    public static boolean isPrivateIp(String ip) {
        try {
            return isPrivateAddress(parseIpLiteral(ip));
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    public static boolean isValidPublicIp(String ip) {
        try {
            return !isPrivateAddress(parseIpLiteral(ip));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static InetAddress parseIpLiteral(String ip) throws UnknownHostException {
        if (ip == null) throw new UnknownHostException("null IP");
        String value = ip.trim();
        if (value.isEmpty()) throw new UnknownHostException("empty IP");
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) throw new UnknownHostException("invalid IPv6 literal");
        } else if (!isIpv4Literal(value)) {
            throw new UnknownHostException("invalid IPv4 literal");
        }
        return InetAddress.getByName(value);
    }

    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean isPrivateAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        if (addr instanceof Inet4Address) return isPrivateIpv4(addr.getAddress());
        if (addr instanceof Inet6Address) return isPrivateIpv6((Inet6Address) addr);
        return true;
    }

    private static boolean isPrivateIpv4(byte[] b) {
        int a = b[0] & 0xFF;
        int c = b[1] & 0xFF;
        int d = b[2] & 0xFF;

        if (a == 10 || a == 127 || a == 0) return true;
        if (a == 172 && c >= 16 && c <= 31) return true;
        if (a == 192 && c == 168) return true;
        if (a == 169 && c == 254) return true;
        if (a == 100 && c >= 64 && c <= 127) return true;
        if (a == 192 && c == 0 && d == 0) return true;
        if (a == 192 && c == 0 && d == 2) return true;
        if (a == 198 && (c == 18 || c == 19)) return true;
        if (a == 198 && c == 51 && d == 100) return true;
        if (a == 203 && c == 0 && d == 113) return true;
        return a >= 224;
    }

    private static boolean isPrivateIpv6(Inet6Address addr) {
        byte[] b = addr.getAddress();
        int a = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        int third = b[2] & 0xFF;
        int fourth = b[3] & 0xFF;
        // ULA fc00::/7
        if ((a & 0xFE) == 0xFC) return true;
        // Link-local fe80::/10
        if (a == 0xFE && (second & 0xC0) == 0x80) return true;
        // 2001:db8::/32 documentation
        if (a == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return true;
        // Teredo 2001::/32
        if (a == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x00) return true;
        // IPv4-mapped ::ffff:x.x.x.x -> recurse on the v4 part
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        if ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            try {
                return isPrivateIpv4(new byte[]{b[12], b[13], b[14], b[15]});
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
