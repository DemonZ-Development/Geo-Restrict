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
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, privacy-first geolocation cache backed by a ConcurrentHashMap
 * and persisted to a JSON file on disk.
 *
 * Saves are debounced â€” at most one write every 5 seconds â€” to avoid
 * excessive disk I/O when many IPs are cached in quick succession.
 */
public class GeoCache {

    private static final long SAVE_DEBOUNCE_MS = 5_000;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final File cacheFile;
    private final Gson gson;
    private final Logger logger;

    private final AtomicBoolean savePending = new AtomicBoolean(false);
    private final AtomicLong lastSaveScheduled = new AtomicLong(0);

    public GeoCache(File cacheFile, Gson gson, Logger logger) {
        this.cacheFile = cacheFile;
        this.gson = gson;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ I/O

    /**
     * Loads the cache from disk. Handles missing or corrupt files gracefully.
     */
    public void load() {
        if (!cacheFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(cacheFile)) {
            Type type = new TypeToken<ConcurrentHashMap<String, CacheEntry>>() {}.getType();
            ConcurrentHashMap<String, CacheEntry> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                cache.putAll(loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load cache file, starting fresh: {}", e.getMessage());
        }
    }

    /**
     * Writes the full cache to disk asynchronously.
     */
    public void save() {
        CompletableFuture.runAsync(() -> {
            try {
                if (cacheFile.getParentFile() != null) {
                    cacheFile.getParentFile().mkdirs();
                }
                try (FileWriter writer = new FileWriter(cacheFile)) {
                    gson.toJson(cache, writer);
                }
            } catch (IOException e) {
                logger.warn("Failed to save cache file: {}", e.getMessage());
            }
        });
    }

    // --------------------------------------------------------- Cache access

    /**
     * Returns the cached GeoResponse for the given IP if it exists and has not expired.
     *
     * @param ip      the IP address key
     * @param ttlDays time-to-live in days
     * @return the cached response, or null if missing/expired
     */
    public GeoResponse get(String ip, int ttlDays) {
        CacheEntry entry = cache.get(ip);
        if (entry == null) {
            return null;
        }

        long ageMs = System.currentTimeMillis() - entry.timestamp;
        long ttlMs = (long) ttlDays * 24 * 60 * 60 * 1000;
        if (ageMs > ttlMs) {
            cache.remove(ip);
            return null;
        }
        return entry.data;
    }

    /**
     * Stores a GeoResponse for the given IP and schedules an async save
     * (debounced to avoid writes on every single put).
     */
    public void put(String ip, GeoResponse response) {
        cache.put(ip, new CacheEntry(response, System.currentTimeMillis()));
        scheduleSave();
    }

    // ------------------------------------------------------------ Purge ops

    /**
     * Clears all cached entries and deletes the cache file.
     */
    public void purgeAll() {
        cache.clear();
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }

    /**
     * Removes entries that are older than the specified TTL.
     *
     * @param ttlDays time-to-live in days
     */
    public void purgeExpired(int ttlDays) {
        long ttlMs = (long) ttlDays * 24 * 60 * 60 * 1000;
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            if (now - entry.getValue().timestamp > ttlMs) {
                it.remove();
            }
        }
        scheduleSave();
    }

    // --------------------------------------------------------------- Stats

    /**
     * Returns current cache statistics.
     */
    public CacheStats getStats() {
        int entryCount = cache.size();
        long fileSizeBytes = cacheFile.exists() ? cacheFile.length() : 0;
        long oldestTimestamp = Long.MAX_VALUE;
        for (CacheEntry entry : cache.values()) {
            if (entry.timestamp < oldestTimestamp) {
                oldestTimestamp = entry.timestamp;
            }
        }
        if (oldestTimestamp == Long.MAX_VALUE) {
            oldestTimestamp = 0;
        }
        return new CacheStats(entryCount, fileSizeBytes, oldestTimestamp);
    }

    // --------------------------------------------------- Debounced save

    private void scheduleSave() {
        long now = System.currentTimeMillis();
        lastSaveScheduled.set(now);

        if (savePending.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(SAVE_DEBOUNCE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                savePending.set(false);
                save();
            });
        }
    }

    // -------------------------------------------------- Inner classes

    /**
     * A single cache entry: the geo data and a timestamp of when it was stored.
     */
    public static class CacheEntry {
        public GeoResponse data;
        public long timestamp;

        public CacheEntry() {}

        public CacheEntry(GeoResponse data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    /**
     * Snapshot of cache statistics.
     */
    public static class CacheStats {
        public final int entryCount;
        public final long fileSizeBytes;
        public final long oldestEntryTimestamp;

        public CacheStats(int entryCount, long fileSizeBytes, long oldestEntryTimestamp) {
            this.entryCount = entryCount;
            this.fileSizeBytes = fileSizeBytes;
            this.oldestEntryTimestamp = oldestEntryTimestamp;
        }
    }
}

