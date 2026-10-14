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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUtilsTest {

    @Test
    void detectsLocalAddressesIncludingMappedAndScopedIpv6() {
        for (String ip : new String[]{"127.0.0.1", "127.255.255.255", "10.0.0.1",
                "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.1.1",
                "0.0.0.0", "::", "::1", "0:0:0:0:0:0:0:1", "fc00::1", "fdff::1",
                "fe80::1", "febf::1", "fe80::1%eth0", "fe80::1%2",
                "::ffff:192.168.1.1", "::ffff:c0a8:101"}) {
            assertTrue(NetworkUtils.isLocalIp(ip), ip);
        }
    }

    @Test
    void localBlockingDoesNotIncludeOtherReservedOrPublicAddresses() {
        for (String ip : new String[]{"1.1.1.1", "172.15.255.255", "172.32.0.1",
                "192.169.0.1", "100.64.0.1", "192.0.2.1", "198.18.0.1", "203.0.113.1",
                "224.0.0.1", "2001:db8::1", "2001::1", "ff02::1", "fbff::1",
                "2606:4700:4700::1111", "::ffff:8.8.8.8", "localhost", "not-an-ip",
                "192.168.1.999", "fe80::1%", "fe80::1%eth0%2", "127.0.0.1%eth0", "", null}) {
            assertFalse(NetworkUtils.isLocalIp(ip), String.valueOf(ip));
        }
    }

    @Test
    void detectsPrivateIpv4() {
        assertTrue(NetworkUtils.isPrivateIp("127.0.0.1"));
        assertTrue(NetworkUtils.isPrivateIp("10.0.0.1"));
        assertTrue(NetworkUtils.isPrivateIp("172.16.5.5"));
        assertTrue(NetworkUtils.isPrivateIp("172.31.255.255"));
        assertTrue(NetworkUtils.isPrivateIp("192.168.0.1"));
        assertTrue(NetworkUtils.isPrivateIp("169.254.1.1"));
        assertTrue(NetworkUtils.isPrivateIp("100.64.0.1"));
        assertTrue(NetworkUtils.isPrivateIp("100.127.255.255"));
        assertTrue(NetworkUtils.isPrivateIp("0.0.0.0"));
    }

    @Test
    void detectsCgnat() {
        assertTrue(NetworkUtils.isPrivateIp("100.64.0.1"));
        assertTrue(NetworkUtils.isPrivateIp("100.127.255.254"));
        assertTrue(NetworkUtils.isPrivateIp("2001:db8::1"));
    }

    @Test
    void allowsPublicIpv4() {
        assertFalse(NetworkUtils.isPrivateIp("8.8.8.8"));
        assertFalse(NetworkUtils.isPrivateIp("1.1.1.1"));
        assertFalse(NetworkUtils.isPrivateIp("172.32.0.1"));
        assertFalse(NetworkUtils.isPrivateIp("11.0.0.1"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertFalse(NetworkUtils.isPrivateIp(null));
        assertFalse(NetworkUtils.isPrivateIp(""));
        assertFalse(NetworkUtils.isPrivateIp("not-an-ip"));
    }

    @Test
    void isValidPublicIpRejectsPrivate() {
        assertFalse(NetworkUtils.isValidPublicIp("192.168.0.1"));
        assertFalse(NetworkUtils.isValidPublicIp("10.0.0.1"));
        assertFalse(NetworkUtils.isValidPublicIp("100.64.0.1"));
        assertFalse(NetworkUtils.isValidPublicIp("192.0.2.1"));
        assertFalse(NetworkUtils.isValidPublicIp("198.19.255.255"));
        assertFalse(NetworkUtils.isValidPublicIp("198.51.100.1"));
        assertFalse(NetworkUtils.isValidPublicIp("203.0.113.1"));
        assertFalse(NetworkUtils.isValidPublicIp("2001:db8::1"));
        assertFalse(NetworkUtils.isValidPublicIp("example.com"));
        assertTrue(NetworkUtils.isValidPublicIp("8.8.8.8"));
        assertTrue(NetworkUtils.isValidPublicIp("2606:4700:4700::1111"));
    }
}
