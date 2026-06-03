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
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeoCache {

    private static final long SAVE_DEBOUNCE_MS = 5_000L;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final File cacheFile;
    private final Gson gson;
    private final Logger logger;
    private final Object ioLock = new Object();
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    public GeoCache(File cacheFile, Gson gson, Logger logger) {
        this.cacheFile = cacheFile;
        this.gson = gson;
        this.logger = logger;
    }

    public void load() {
        if (!cacheFile.exists()) return;
        try (var reader = Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<ConcurrentHashMap<String, CacheEntry>>() {}.getType();
            ConcurrentHashMap<String, CacheEntry> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                long cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000;
                for (Map.Entry<String, CacheEntry> e : loaded.entrySet()) {
                    if (e.getValue() != null && e.getValue().timestamp > cutoff) {
                        cache.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Cache file unreadable, starting fresh: {}", e.getMessage());
        }
    }

    public void save() {
        synchronized (ioLock) {
            try {
                File parent = cacheFile.getParentFile();
                if (parent != null) parent.mkdirs();
                if (cache.isEmpty()) {
                    Files.deleteIfExists(cacheFile.toPath());
                    return;
                }
                File tmp = new File(parent, cacheFile.getName() + ".tmp");
                Files.writeString(tmp.toPath(), gson.toJson(cache), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignored) {
                    Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.warn("Cache save failed: {}", e.getMessage());
            }
        }
    }

    public GeoResponse get(String ip, int ttlDays) {
        CacheEntry entry = cache.get(ip);
        if (entry == null) return null;
        long ttlMs = Math.max(1, ttlDays) * 24L * 60 * 60 * 1000;
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(ip, entry);
            return null;
        }
        return entry.data;
    }

    public void put(String ip, GeoResponse response, int maxEntries) {
        if (response == null) return;
        cache.put(ip, new CacheEntry(response, System.currentTimeMillis()));
        if (maxEntries > 0 && cache.size() > maxEntries) {
            evictOldest(maxEntries);
        }
        scheduleSave();
    }

    public void purgeAll() {
        cache.clear();
        try {
            Files.deleteIfExists(cacheFile.toPath());
        } catch (IOException e) {
            logger.warn("Cache delete failed: {}", e.getMessage());
        }
    }

    public void purgeExpired(int ttlDays) {
        long ttlMs = Math.max(1, ttlDays) * 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> e = it.next();
            if (now - e.getValue().timestamp > ttlMs) it.remove();
        }
        scheduleSave();
    }

    public CacheStats getStats() {
        long oldest = Long.MAX_VALUE;
        for (CacheEntry e : cache.values()) {
            if (e.timestamp < oldest) oldest = e.timestamp;
        }
        return new CacheStats(
            cache.size(),
            cacheFile.exists() ? cacheFile.length() : 0,
            oldest == Long.MAX_VALUE ? 0 : oldest
        );
    }

    private void evictOldest(int target) {
        if (cache.size() <= target) return;
        int toRemove = cache.size() - target;
        cache.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().timestamp, b.getValue().timestamp))
            .limit(toRemove)
            .map(Map.Entry::getKey)
            .forEach(cache::remove);
    }

    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(SAVE_DEBOUNCE_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                savePending.set(false);
                save();
            });
        }
    }

    public static class CacheEntry {
        public GeoResponse data;
        public long timestamp;

        public CacheEntry() {}
        public CacheEntry(GeoResponse data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }

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
