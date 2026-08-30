package zip.linuxaddict.georestrict.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoResponse;
import zip.linuxaddict.georestrict.GeoRestrictService;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeoRestrictAPITest {

    private GeoRestrictService service;
    private GeoCache cache;
    private File tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = File.createTempFile("grapi-svc", "");
        tmpDir.delete();
        tmpDir.mkdirs();
        File cacheFile = new File(tmpDir, "cache.json");
        Logger log = LoggerFactory.getLogger("test");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        GeoConfig config = new GeoConfig();
        config.countries = Arrays.asList("CN", "RU");
        config.countryMode = GeoConfig.RestrictionMode.BLOCKLIST;
        config.asnMode = GeoConfig.RestrictionMode.DISABLED;
        config.vpnCheckEnabled = true;

        service = new GeoRestrictService(config, log, cache);
        GeoRestrictAPI.register(service, cache);
    }

    @AfterEach
    void tearDown() {
        GeoRestrictAPI.unregister();
        service.shutdown();
        deleteRecursively(tmpDir);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteRecursively(c);
        f.delete();
    }

    @Test
    void isAvailableReturnsTrueWhenRegistered() {
        assertTrue(GeoRestrictAPI.isAvailable());
        assertNotNull(GeoRestrictAPI.getService());
    }

    @Test
    void checkIpReturnsCheckResult() {
        GeoRestrictService.CheckResult result = GeoRestrictAPI.checkIp("127.0.0.1", "tester").join();
        assertTrue(result.allowed);
    }

    @Test
    void isAllowedReturnsBoolean() {
        Boolean allowed = GeoRestrictAPI.isAllowed("127.0.0.1", "tester").join();
        assertTrue(allowed);
    }

    @Test
    void getCachedResponseReturnsCachedData() {
        GeoResponse r = new GeoResponse();
        r.countryCode = "US";
        r.asn = "AS15169";
        cache.put("8.8.8.8", r, 1000);

        GeoResponse cached = GeoRestrictAPI.getCachedResponse("8.8.8.8");
        assertNotNull(cached);
        assertEquals("US", cached.countryCode);
    }

    @Test
    void getVersionReturnsNonNullString() {
        assertNotNull(GeoRestrictAPI.getVersion());
    }

    @Test
    void unregisterClearsApiState() {
        GeoRestrictAPI.unregister();
        assertFalse(GeoRestrictAPI.isAvailable());
        assertThrows(IllegalStateException.class, GeoRestrictAPI::getService);
    }

    @Test
    void lookupAndGetCountryCodeReturnsGeoData() {
        GeoResponse r = new GeoResponse();
        r.ip = "1.1.1.1";
        r.countryCode = "AU";
        r.countryName = "Australia";
        r.asn = "AS15169";
        r.asName = "Google Cloud DataCenter";
        r.isVpn = true;
        cache.put("1.1.1.1", r, 1000);

        GeoResponse res = GeoRestrictAPI.lookup("1.1.1.1").join();
        assertNotNull(res);
        assertEquals("AU", res.countryCode);
        assertEquals("Australia", res.countryName);

        String cc = GeoRestrictAPI.getCountryCode("1.1.1.1").join();
        assertEquals("AU", cc);

        Boolean isVpn = GeoRestrictAPI.isVpn("1.1.1.1").join();
        assertTrue(isVpn);

        String asn = GeoRestrictAPI.getAsn("1.1.1.1").join();
        assertEquals("AS15169", asn);

        String isp = GeoRestrictAPI.getIsp("1.1.1.1").join();
        assertEquals("Google Cloud DataCenter", isp);
    }

    @Test
    void nameAndFlagGettersReturnCachedValues() {
        GeoResponse r = new GeoResponse();
        r.ip = "8.8.4.4";
        r.countryCode = "US";
        r.countryName = "United States";
        r.asn = "AS15169";
        r.asName = "Google LLC";
        r.isProxy = true;
        r.isHosting = true;
        r.isMobile = false;
        cache.put("8.8.4.4", r, 1000);

        assertEquals("United States", GeoRestrictAPI.getCountryName("8.8.4.4").join());
        assertEquals("Google LLC", GeoRestrictAPI.getAsnName("8.8.4.4").join());
        assertTrue(GeoRestrictAPI.isProxy("8.8.4.4").join());
        assertTrue(GeoRestrictAPI.isHosting("8.8.4.4").join());
        assertFalse(GeoRestrictAPI.isMobile("8.8.4.4").join());
    }

    @Test
    void isBlockedMirrorsTheRuleDecision() {
        assertFalse(GeoRestrictAPI.isBlocked("127.0.0.1", "tester").join());
    }

    @Test
    void lookupAllBatchesCachedAndLocalLookupsInOrder() {
        GeoResponse first = new GeoResponse();
        first.ip = "8.8.4.4";
        first.countryCode = "US";
        cache.put("8.8.4.4", first, 1000);

        GeoResponse second = new GeoResponse();
        second.ip = "1.1.1.1";
        second.countryCode = "AU";
        cache.put("1.1.1.1", second, 1000);

        Map<String, GeoResponse> results = GeoRestrictAPI.lookupAll(
            Arrays.asList("10.0.0.5", " 8.8.4.4 ", null, "", "1.1.1.1")).join();

        assertEquals(3, results.size());
        assertIterableEquals(Arrays.asList("10.0.0.5", "8.8.4.4", "1.1.1.1"), results.keySet());
        assertNull(results.get("10.0.0.5"));
        assertEquals("US", results.get("8.8.4.4").countryCode);
        assertEquals("AU", results.get("1.1.1.1").countryCode);
    }

    @Test
    void lookupAllHandlesEmptyAndNullInput() {
        assertTrue(GeoRestrictAPI.lookupAll(null).join().isEmpty());
        assertTrue(GeoRestrictAPI.lookupAll(java.util.Collections.emptyList()).join().isEmpty());
    }

    @Test
    void cachedCountryCodeHitsOnlyFreshOfflineData() {
        GeoResponse r = new GeoResponse();
        r.countryCode = "DE";
        cache.put("203.0.113.7", r, 1000);

        assertEquals("DE", GeoRestrictAPI.getCachedCountryCode("203.0.113.7"));
        assertNull(GeoRestrictAPI.getCachedCountryCode("198.51.100.9"));
    }

    @Test
    void cacheSizeAndPurgeRoundTrip() {
        GeoResponse r = new GeoResponse();
        r.countryCode = "US";
        cache.put("8.8.8.8", r, 1000);
        cache.put("8.8.4.4", r, 1000);
        assertTrue(GeoRestrictAPI.getCacheSize() >= 2);

        GeoRestrictAPI.purgeCache();
        assertEquals(0, GeoRestrictAPI.getCacheSize());
        assertNull(GeoRestrictAPI.getCachedResponse("8.8.8.8"));
    }

    @Test
    void cacheHelpersStaySafeAfterUnregister() {
        GeoRestrictAPI.unregister();
        assertEquals(0, GeoRestrictAPI.getCacheSize());
        assertNull(GeoRestrictAPI.getCachedCountryCode("8.8.8.8"));
        GeoRestrictAPI.purgeCache();
    }
}