package zip.linuxaddict.georestrict.api;

import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoResponse;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public entry point other plugins call. Everything here resolves on the
 * plugin's lookup executor; nothing in this class ever blocks the caller.
 *
 * Lookup-based getters return {@code null} (or {@code false}) when the
 * gateway cannot answer. Rule checks ({@link #isAllowed}, {@link #isBlocked})
 * fall back to whatever {@code blockOnLookupFailure} is set to in config.
 */
public final class GeoRestrictAPI {

    private static final int CACHE_TTL_DAYS = 30;

    private static volatile GeoRestrictService service;
    private static volatile GeoCache cache;

    private GeoRestrictAPI() {}

    public static void register(GeoRestrictService serviceInstance, GeoCache cacheInstance) {
        service = serviceInstance;
        cache = cacheInstance;
    }

    public static void unregister() {
        service = null;
        cache = null;
    }

    /** @return true once {@code register(...)} has been called by the plugin. */
    public static boolean isAvailable() {
        return service != null;
    }

    /** Throws if the plugin is not enabled. */
    public static GeoRestrictService getService() {
        GeoRestrictService s = service;
        if (s == null) {
            throw new IllegalStateException("GeoRestrict API is not initialized. Is GeoRestrict enabled?");
        }
        return s;
    }

    /** Run an IP through the active rule set. */
    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName) {
        return getService().checkIp(ip, playerName, false);
    }

    /** Same as {@link #checkIp(String, String)} but skip rule evaluation. */
    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, boolean bypass) {
        return getService().checkIp(ip, playerName, null, bypass);
    }

    /** @param uuid used to correlate the result with a pending join; may be null. */
    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, UUID uuid) {
        return getService().checkIp(ip, playerName, uuid, false);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, UUID uuid, boolean bypass) {
        return getService().checkIp(ip, playerName, uuid, bypass);
    }

    /** true if the IP passes every active rule. */
    public static CompletableFuture<Boolean> isAllowed(String ip, String playerName) {
        return checkIp(ip, playerName).thenApply(result -> result.allowed);
    }

    /** true if the IP would be kicked by the active rules. Opposite of {@link #isAllowed}. */
    public static CompletableFuture<Boolean> isBlocked(String ip, String playerName) {
        return checkIp(ip, playerName).thenApply(result -> !result.allowed);
    }

    /** Raw lookup. No rule evaluation, no Discord logging. null on failure. */
    public static CompletableFuture<GeoResponse> lookup(String ip) {
        return getService().lookup(ip);
    }

    /** Cached or freshly looked up GeoResponse, or null if expired/unknown. */
    public static GeoResponse getCachedResponse(String ip) {
        GeoCache c = cache;
        if (c == null) return null;
        return c.get(ip, CACHE_TTL_DAYS);
    }

    /** ISO 3166-1 alpha-2 country code (e.g. "US"). */
    public static CompletableFuture<String> getCountryCode(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.countryCode : null);
    }

    /** Long country name (e.g. "United States"). */
    public static CompletableFuture<String> getCountryName(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.countryName : null);
    }

    /** AS number string (e.g. "AS15169"). */
    public static CompletableFuture<String> getAsn(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.asn : null);
    }

    /** AS organization name. Falls back to {@code provider} if the AS name is empty. */
    public static CompletableFuture<String> getIsp(String ip) {
        return lookup(ip).thenApply(res -> res != null ? (res.asName != null ? res.asName : res.provider) : null);
    }

    /** AS organization name only; never falls back to the raw provider tag. */
    public static CompletableFuture<String> getAsnName(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.asName : null);
    }

    /** True if any provider-reported VPN/hosting/proxy flag is set, or if the AS name matches configured keywords. */
    public static CompletableFuture<Boolean> isVpn(String ip) {
        return lookup(ip).thenApply(res -> getService().isVpn(res));
    }

    /** Provider-reported proxy flag. */
    public static CompletableFuture<Boolean> isProxy(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isProxy);
    }

    /** Provider-reported hosting/datacenter flag. */
    public static CompletableFuture<Boolean> isHosting(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isHosting);
    }

    /** Provider-reported mobile carrier flag. */
    public static CompletableFuture<Boolean> isMobile(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isMobile);
    }

    /**
     * Parallel batch lookup. Blanks, nulls and duplicates are dropped.
     * Result map preserves first-seen order. Failed IPs map to null.
     */
    public static CompletableFuture<Map<String, GeoResponse>> lookupAll(Collection<String> ips) {
        Map<String, CompletableFuture<GeoResponse>> pending = new LinkedHashMap<>();
        if (ips != null) {
            for (String ip : ips) {
                String normalized = ip == null ? "" : ip.trim();
                if (!normalized.isEmpty() && !pending.containsKey(normalized)) {
                    pending.put(normalized, lookup(normalized));
                }
            }
        }
        return CompletableFuture.allOf(pending.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, GeoResponse> results = new LinkedHashMap<>(pending.size());
                pending.forEach((address, future) -> results.put(address, future.join()));
                return results;
            });
    }

    /** Country code from the local cache. No network call. */
    public static String getCachedCountryCode(String ip) {
        GeoResponse cached = getCachedResponse(ip);
        return cached != null ? cached.countryCode : null;
    }

    /** @return current local cache entry count, 0 if plugin is disabled. */
    public static int getCacheSize() {
        GeoCache c = cache;
        return c == null ? 0 : c.getStats().entryCount;
    }

    /** Drops every entry from the local cache. No-op if plugin is disabled. */
    public static void purgeCache() {
        GeoCache c = cache;
        if (c != null) c.purgeAll();
    }

    /** Plugin version (e.g. "2.0.2"). */
    public static String getVersion() {
        return PluginInfo.VERSION;
    }
}