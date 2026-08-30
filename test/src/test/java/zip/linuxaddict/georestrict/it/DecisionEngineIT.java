/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.GeoRestrictService.CheckResult;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEngineIT {

    private static final Logger log = LoggerFactory.getLogger("DecisionEngineIT");

    private MockGateway gw;
    private GeoRestrictService svc;
    private GeoCache cache;
    private GeoConfig config;
    private File tmp;

    @BeforeEach
    void setUp() throws Exception {
        gw = new MockGateway();
        gw.with("5.6.7.8",   "RU", "12389", "Rostelecom",        false, false, false)
          .with("1.2.3.4",   "US", "15169", "Google LLC",        false, false, false)
          .with("45.1.1.9", "CN", "4809", "China Telecom",    false, false, false)
          .with("80.1.1.7", "DE", "3320", "Deutsche Telekom", false, true, false)
          .with("64.1.1.45", "US", "7922", "Comcast VPN LLC",  false, false, false)
          .withRaw("8.8.4.4", "{}");

        tmp = File.createTempFile("gr-it", "");
        tmp.delete();
        tmp.mkdirs();
        File cacheFile = new File(tmp, "cache.json");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        config = new GeoConfig();
        config.gatewayUrl = gw.baseUrl();
        config.countries = Arrays.asList("RU", "CN");
        config.countryMode = GeoConfig.RestrictionMode.BLOCKLIST;
        config.asnMode = GeoConfig.RestrictionMode.DISABLED;
        config.asns = Arrays.asList();
        config.vpnCheckEnabled = true;
        config.vpnKeywords = Arrays.asList("vpn", "proxy");
        config.blockOnLookupFailure = true;
        config.connectionTimeoutMs = 2000;
        config.lookupThreads = 2;
        config.maxCacheEntries = 1000;
        config.cacheTtlDays = 30;
        config.kickMessageCountry = "Your country is not allowed on this server.";
        config.kickMessageVpn = "VPN or proxy connections are not allowed.";
        config.kickMessageLookupFailure = "Geo verification is temporarily unavailable. Please try again later.";
        svc = new GeoRestrictService(config, log, cache);
    }

    @AfterEach
    void tearDown() {
        if (svc != null) svc.shutdown();
        if (gw != null) gw.close();
        delete(tmp);
    }

    private void delete(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) delete(c);
        }
        f.delete();
    }

    private CheckResult check(String ip) {
        return svc.checkIp(ip, "tester").join();
    }

    @Test
    void countryBlocklistBlocksRU() {
        assertFalse(check("5.6.7.8").allowed);
    }

    @Test
    void countryBlocklistAllowsUS() {
        assertTrue(check("1.2.3.4").allowed);
    }

    @Test
    void countryBlocklistBlocksCN() {
        assertFalse(check("45.1.1.9").allowed);
    }

    @Test
    void vpnHostingFlagBlocks() {
        assertFalse(check("80.1.1.7").allowed);
    }

    @Test
    void vpnKeywordBlocks() {
        assertFalse(check("64.1.1.45").allowed);
    }

    @Test
    void privateIpAllowed() {
        assertTrue(check("127.0.0.1").allowed);
    }

    @Test
    void privateIpRangeAllowed() {
        assertTrue(check("10.0.0.5").allowed);
    }

    @Test
    void bypassAllowed() {
        assertTrue(svc.checkIp("5.6.7.8", "tester", true).join().allowed);
    }

    @Test
    void lookupFailureBlockedWhenFailClosed() {
        assertFalse(check("9.9.9.9").allowed);
    }

    @Test
    void gatewayUrlEndingWithQuestionMarkIsSupported() {
        config.gatewayUrl = gw.baseUrl() + "?";
        assertTrue(check("1.2.3.4").allowed);
    }

    @Test
    void incompleteGatewayResponseFailsClosed() {
        assertFalse(check("8.8.4.4").allowed);
    }

    @Test
    void allowlistModeAllowsOnlyListed() {
        GeoConfig cfg = new GeoConfig();
        cfg.gatewayUrl = gw.baseUrl();
        cfg.countries = Arrays.asList("US");
        cfg.countryMode = GeoConfig.RestrictionMode.ALLOWLIST;
        cfg.vpnCheckEnabled = false;
        cfg.blockOnLookupFailure = true;
        GeoRestrictService s2 = new GeoRestrictService(cfg, log, cache);
        try {
            assertFalse(s2.checkIp("5.6.7.8", "tester").join().allowed);
            assertTrue(s2.checkIp("1.2.3.4", "tester").join().allowed);
        } finally {
            s2.shutdown();
        }
    }

    @Test
    void asnBlocklistBlocks() {
        GeoConfig cfg = new GeoConfig();
        cfg.gatewayUrl = gw.baseUrl();
        cfg.countryMode = GeoConfig.RestrictionMode.DISABLED;
        cfg.asnMode = GeoConfig.RestrictionMode.BLOCKLIST;
        cfg.asns = Arrays.asList("15169");
        cfg.vpnCheckEnabled = false;
        cfg.blockOnLookupFailure = true;
        GeoRestrictService s2 = new GeoRestrictService(cfg, log, cache);
        try {
            assertFalse(s2.checkIp("1.2.3.4", "tester").join().allowed);
            assertTrue(s2.checkIp("5.6.7.8", "tester").join().allowed);
        } finally {
            s2.shutdown();
        }
    }

    @Test
    void failOpenAllowsOnLookupFailure() {
        GeoConfig cfg = new GeoConfig();
        cfg.gatewayUrl = gw.baseUrl();
        cfg.countryMode = GeoConfig.RestrictionMode.DISABLED;
        cfg.vpnCheckEnabled = false;
        cfg.blockOnLookupFailure = false;
        GeoRestrictService s2 = new GeoRestrictService(cfg, log, cache);
        try {
            assertTrue(s2.checkIp("9.9.9.9", "tester").join().allowed);
        } finally {
            s2.shutdown();
        }
    }

    @Test
    void cacheHitReusesResult() {
        assertFalse(check("5.6.7.8").allowed);
        assertFalse(check("5.6.7.8").allowed);
    }
}
