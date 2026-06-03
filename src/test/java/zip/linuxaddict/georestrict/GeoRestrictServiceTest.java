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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        config.directFallbackEnabled = false;
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
    void privateIpAlwaysAllowed() {
        GeoRestrictService.CheckResult r = service.checkIp("127.0.0.1", "tester").join();
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
        // No real network in tests; we expect lookup to fail and (with
        // blockOnLookupFailure=true) the player to be blocked.
        GeoRestrictService.CheckResult r = service.checkIp("8.8.8.8", "tester", false).join();
        assertFalse(r.allowed);
    }
}
