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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUtilsTest {

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
        assertTrue(NetworkUtils.isValidPublicIp("8.8.8.8"));
    }
}
