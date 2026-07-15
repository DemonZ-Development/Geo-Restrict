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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeoCacheTest {

    private final Logger log = LoggerFactory.getLogger("test");
    private final Gson gson = new Gson();

    @Test
    void putAndGetRoundTrip() throws Exception {
        File tmp = File.createTempFile("geo-cache", ".json");
        tmp.delete();
        GeoCache cache = new GeoCache(tmp, gson, log);
        cache.load();

        GeoResponse r = new GeoResponse();
        r.countryCode = "US";
        r.asn = "AS15169";
        cache.put("8.8.8.8", r, 1000);
        GeoResponse got = cache.get("8.8.8.8", 30);
        assertNotNull(got);
        assertEquals("US", got.countryCode);
        assertEquals("AS15169", got.asn);

        cache.save();
        tmp.delete();
    }

    @Test
    void evictsOldestWhenOverCapacity() throws Exception {
        File tmp = File.createTempFile("geo-cache-cap", ".json");
        tmp.delete();
        GeoCache cache = new GeoCache(tmp, gson, log);
        cache.load();
        for (int i = 0; i < 10; i++) {
            GeoResponse r = new GeoResponse();
            r.countryCode = "US";
            cache.put("1.1.1." + i, r, 5);
        }
        GeoCache.CacheStats stats = cache.getStats();
        assertEquals(5, stats.entryCount);
        tmp.delete();
    }

    @Test
    void purgeAllClearsEntries() throws Exception {
        File tmp = File.createTempFile("geo-cache-purge", ".json");
        tmp.delete();
        GeoCache cache = new GeoCache(tmp, gson, log);
        cache.load();
        GeoResponse r = new GeoResponse();
        r.countryCode = "US";
        cache.put("8.8.8.8", r, 1000);
        cache.purgeAll();
        assertNull(cache.get("8.8.8.8", 30));
        tmp.delete();
    }
}
