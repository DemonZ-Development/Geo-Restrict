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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeoRestrictServiceTest {

    private GeoRestrictService service;
    private GeoCache cache;
    private GeoConfig config;
    private File tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = File.createTempFile("grs-svc", "");
        tmpDir.delete();
        tmpDir.mkdirs();
        File cacheFile = new File(tmpDir, "cache.json");
        Logger log = LoggerFactory.getLogger("test");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();
        config = new GeoConfig();
        config.countries = Arrays.asList("CN", "RU");
        config.countryMode = GeoConfig.RestrictionMode.BLOCKLIST;
        config.asnMode = GeoConfig.RestrictionMode.DISABLED;
        config.vpnCheckEnabled = true;
        config.connectionTimeoutMs = 500;
        config.gatewayUrl = "http://127.0.0.1:1/";
        config.blockOnLookupFailure = true;
        service = new GeoRestrictService(config, log, cache);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        deleteRecursively(tmpDir);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteRecursively(c);
        f.delete();
    }

    @Test
    void privateIpAllowedByDefault() {
        GeoRestrictService.CheckResult r = service.checkIp("127.0.0.1", "tester").join();
        assertTrue(r.allowed);
    }

    @Test
    void geyserPublicSocketIsNotBlockedBecauseItsJavaTransportIsLocal() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        org.geysermc.geyser.api.GeyserApi.CONNECTIONS.put(uuid,
            new zip.linuxaddict.georestrict.floodgate.GeyserHandlerTest.Session());
        try {
            config.blockLocalConnections = true;
            config.floodgate.enabled = true;
            assertNull(service.checkLocalIp("127.0.0.1", "bedrock", uuid, false));
            config.floodgate.enabled = false;
            assertFalse(service.checkLocalIp("127.0.0.1", "bedrock", uuid, false).allowed);
        } finally {
            org.geysermc.geyser.api.GeyserApi.CONNECTIONS.clear();
        }
    }

    @Test
    void localBlockingRejectsLocalAddressesWithConfiguredReason() {
        config.blockLocalConnections = true;
        config.blockOnLookupFailure = false;
        config.kickMessageLocalConnection = "LAN joins disabled";
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "::1", "fd00::1"}) {
            GeoRestrictService.CheckResult result = service.checkIp(ip, "tester").join();
            assertFalse(result.allowed, ip);
            assertEquals("LAN joins disabled", result.reason);
            assertEquals("LOCAL", result.info.countryCode);
        }
        assertEquals(0, cache.getStats().entryCount);
    }

    @Test
    void explicitBypassStillAllowsLocalConnections() {
        config.blockLocalConnections = true;
        assertTrue(service.checkIp("127.0.0.1", "tester", true).join().allowed);
    }

    @Test
    void synchronousLocalCheckDefersPublicAddressesAndPreservesBypass() {
        config.blockLocalConnections = true;
        service.shutdown();
        assertFalse(service.checkLocalIp("::1", "tester", null, false).allowed);
        assertTrue(service.checkLocalIp("127.0.0.1", "tester", null, true).allowed);
        assertNull(service.checkLocalIp("8.8.8.8", "tester", null, false));
        assertEquals(0, cache.getStats().entryCount);
    }

    @Test
    void localCountryBlocklistWorksWithoutEnablingTheNewFlag() {
        config.countries = Arrays.asList("LOCAL", "CN");
        GeoRestrictService.CheckResult result = service.checkIp("127.0.0.1", "tester").join();
        assertFalse(result.allowed);
        assertEquals(config.kickMessageCountry, result.reason);
        assertEquals("LOCAL", result.info.countryCode);
        config.countryMode = GeoConfig.RestrictionMode.DISABLED;
        assertTrue(service.checkIp("127.0.0.1", "tester").join().allowed);
    }

    @Test
    void localBlockingLeavesReservedAndInvalidInputsUnchanged() {
        config.blockLocalConnections = true;
        assertTrue(service.checkIp("100.64.0.1", "tester").join().allowed);
        assertTrue(service.checkIp("2001:db8::1", "tester").join().allowed);
        assertTrue(service.checkIp("not-an-ip", "tester").join().allowed);
    }

    @Test
    void reloadCanEnableAndDisableLocalBlocking() {
        GeoConfig enabled = new GeoConfig();
        enabled.blockLocalConnections = true;
        service.setConfig(enabled);
        assertFalse(service.checkIp("192.168.1.1", "tester").join().allowed);
        service.setConfig(new GeoConfig());
        assertTrue(service.checkIp("192.168.1.1", "tester").join().allowed);
    }

    @Test
    void reservedPublicLookingIpNeverLeavesThePlugin() {
        GeoRestrictService.CheckResult r = service.checkIp("100.64.0.1", "tester").join();
        assertTrue(r.allowed);
    }

    @Test
    void invalidIpAlwaysAllowed() {
        GeoRestrictService.CheckResult r = service.checkIp("not-an-ip", "tester").join();
        assertTrue(r.allowed);
    }

    @Test
    void bypassAlwaysAllowed() {
        GeoRestrictService.CheckResult r = service.checkIp("8.8.8.8", "tester", true).join();
        assertTrue(r.allowed);
    }

    @Test
    void blockedIpIsDeniedWhenFallbackFails() {
        GeoRestrictService.CheckResult r = service.checkIp("8.8.8.8", "tester", false).join();
        assertFalse(r.allowed);
    }
}
