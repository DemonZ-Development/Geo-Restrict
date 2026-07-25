/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.api;

import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoResponse;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;

import java.util.concurrent.CompletableFuture;

/**
 * Developer API for GeoRestrict.
 * Provides access to IP geographic lookups, cache queries, and restriction checks across all platforms.
 */
public final class GeoRestrictAPI {

    private static volatile GeoRestrictService service;
    private static volatile GeoCache cache;

    private GeoRestrictAPI() {}

    /**
     * Internal method to register the active service instance on plugin enable.
     *
     * @param serviceInstance active GeoRestrictService instance
     * @param cacheInstance active GeoCache instance
     */
    public static void register(GeoRestrictService serviceInstance, GeoCache cacheInstance) {
        service = serviceInstance;
        cache = cacheInstance;
    }

    /**
     * Internal method to unregister the active service instance on plugin disable.
     */
    public static void unregister() {
        service = null;
        cache = null;
    }

    /**
     * Checks if the GeoRestrict API is initialized and ready.
     *
     * @return true if GeoRestrict is loaded and active
     */
    public static boolean isAvailable() {
        return service != null;
    }

    /**
     * Gets the underlying GeoRestrictService instance.
     *
     * @return active service instance
     * @throws IllegalStateException if GeoRestrict plugin is not enabled
     */
    public static GeoRestrictService getService() {
        GeoRestrictService s = service;
        if (s == null) {
            throw new IllegalStateException("GeoRestrict API is not initialized. Is GeoRestrict enabled?");
        }
        return s;
    }

    /**
     * Asynchronously checks an IP address against active country, ASN, and VPN restriction rules.
     *
     * @param ip player IP address (IPv4 or IPv6)
     * @param playerName player name for logging/bypass tracking
     * @return CompletableFuture containing the CheckResult
     */
    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName) {
        return getService().checkIp(ip, playerName, false);
    }

    /**
     * Asynchronously checks an IP address with optional bypass flag.
     *
     * @param ip player IP address
     * @param playerName player name
     * @param bypass if true, bypasses rule evaluation and returns an allowed result
     * @return CompletableFuture containing the CheckResult
     */
    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, boolean bypass) {
        return getService().checkIp(ip, playerName, null, bypass);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, java.util.UUID uuid) {
        return getService().checkIp(ip, playerName, uuid, false);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, java.util.UUID uuid, boolean bypass) {
        return getService().checkIp(ip, playerName, uuid, bypass);
    }

    /**
     * Convenience method to check if an IP address is allowed.
     *
     * @param ip player IP address
     * @param playerName player name
     * @return CompletableFuture resolving to true if connection is allowed, false if blocked
     */
    public static CompletableFuture<Boolean> isAllowed(String ip, String playerName) {
        return checkIp(ip, playerName).thenApply(result -> result.allowed);
    }

    /**
     * Retrieves a cached GeoResponse for an IP address if available and not expired.
     *
     * @param ip player IP address
     * @return GeoResponse object if cached, null otherwise
     */
    public static GeoResponse getCachedResponse(String ip) {
        GeoCache c = cache;
        if (c == null) return null;
        return c.get(ip, 30);
    }

    /**
     * Gets the current plugin version string (e.g. "2.0.1").
     *
     * @return version string
     */
    public static String getVersion() {
        return PluginInfo.VERSION;
    }
}
